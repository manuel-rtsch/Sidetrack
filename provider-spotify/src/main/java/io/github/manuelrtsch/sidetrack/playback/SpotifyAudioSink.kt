package io.github.manuelrtsch.sidetrack.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import io.github.manuelrtsch.sidetrack.SidetrackDebug
import io.github.manuelrtsch.sidetrack.bridge.NativeBridge
import io.github.manuelrtsch.sidetrack.bridge.SpotifyNative
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Drains PCM from librespot into a streaming [AudioTrack].
 *
 * The native sink's bounded channel blocks when this consumer stops, and that backpressure keeps
 * librespot's decode position tied to the speaker. The consequence shapes this class: the player
 * thread only processes pause/play/load while the drain runs, so pause and stop must keep draining
 * until the player acknowledges. Pause therefore keeps the [AudioTrack] alive and retains the PCM
 * still queued behind it, which makes resume sample-continuous.
 *
 * Three rules keep item changes from becoming audible gaps:
 *  - Prime a track before it plays: starting one empty underruns and Android restarts it.
 *  - Reuse a track across items of the same format: releasing it tears down the AudioFlinger track
 *    and re-configures the output route, which costs hundreds of milliseconds on Bluetooth.
 *  - Never write blocking inside the HAL: `WRITE_BLOCKING` cannot be interrupted, so a track change
 *    would sit in the HAL and freeze the caller.
 */
class SpotifyAudioSink(
    private val native: SpotifyNative = NativeBridge,
    private val scope: CoroutineScope,
    /** Reports a drain that stopped making progress; reassigned by the session that owns the player. */
    var onFailure: (Throwable) -> Unit = {},

    /**
     * Reports when the drain has no PCM (`true`) and when audio flows again (`false`), so the UI can
     * freeze the seek bar instead of pretending the track advances.
     */
    var onBuffering: (Boolean) -> Unit = {}
) {
    /** Written by the lifecycle operations, read by the UI and the service on other threads. */
    @Volatile
    private var track: AudioTrack? = null
    private var drain: Job? = null

    /** Set when a failed write left the track in an unknown state, so the next start builds one. */
    @Volatile
    private var trackStale = false

    /**
     * Serializes track lifecycle operations: releasing or flushing while the drain coroutine writes
     * crashes inside libaudioclient. Every public operation takes it and stops the drain first.
     */
    private val lock = Mutex()

    private val audioTrack = AudioTrackBuilder(native) { onFailure(it) }

    /** PCM read from the native channel after the AudioTrack was paused. */
    private val pendingPcm = ArrayDeque<ByteArray>()

    /** Position of the first frame written to the current AudioTrack. */
    private var startPositionMs = 0L
    private var startHeadFrames = 0L
    private var lastHeadFrames = 0L
    private var headWrapOffset = 0L

    val isRunning: Boolean get() = drain?.isActive == true

    /** Starts a new track. Discards any audio left over from the previous one. */
    suspend fun start(positionMs: Long = 0L) = lock.withLock {
        startLocked(positionMs)
    }

    private suspend fun startLocked(positionMs: Long) {
        stopDrain()
        pendingPcm.clear()
        // Unblock a player thread still waiting to write to the previous track: the load command
        // cannot be processed until it is free.
        native.drainAudio()

        val format = audioTrack.readFormat()
        SidetrackDebug.log("audio.start format=${format.sampleRate}Hz channels=${format.channels}")
        val reused = track?.takeIf { canReuse(it, format) }
        val output = if (reused != null) {
            // Same PCM format: keep the track, flushed, since the next item starts elsewhere.
            runCatching { reused.pause() }
            runCatching { reused.flush() }
            reused
        } else {
            releaseTrack()
            audioTrack.build(format) ?: return
        }
        track = output
        trackStale = false
        startPositionMs = positionMs
        startHeadFrames = 0L
        lastHeadFrames = 0L
        headWrapOffset = 0L
        SidetrackDebug.log(
            "audio.start reused=${reused != null} bufferFrames=${audioTrack.bufferBytes / BYTES_PER_FRAME} positionMs=$positionMs"
        )
        // Playback starts inside the drain, once the track actually holds audio.
        startDrain(output, startWhenPrefilled = true)
    }

    /**
     * Resumes the paused track, replaying the PCM captured at pause before new PCM.
     *
     * @return true when the existing AudioTrack was reused; false means the buffer was lost and the
     *   player must be realigned with [positionMs].
     */
    suspend fun resume(positionMs: Long): Boolean = lock.withLock {
        val output = track
        if (output == null || output.playState != AudioTrack.PLAYSTATE_PAUSED) {
            startLocked(positionMs)
            return@withLock false
        }
        SidetrackDebug.log("audio.resume pending=${pendingPcm.size}")
        // The track still holds the audio it was paused with, so it can start immediately.
        runCatching { output.play() }
        startDrain(output, startWhenPrefilled = false)
        true
    }

    /**
     * Pauses without releasing: the track's buffer and the PCM still queued in the channel both
     * survive, so resume is sample-continuous.
     */
    suspend fun pause() = lock.withLock {
        stopDrain()
        capturePendingPcm()
        runCatching { track?.pause() }
        SidetrackDebug.log("audio.pause pending=${pendingPcm.size}")
    }

    /**
     * Drops the item's audio but keeps the track, so the next item starts on one already wired into
     * AudioFlinger and its output route.
     */
    suspend fun reset() = lock.withLock {
        stopDrain()
        pendingPcm.clear()
        native.drainAudio()
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        SidetrackDebug.log("audio.reset stale=$trackStale hasTrack=${track != null}")
    }

    /** Stops and releases the track. Used when stopping playback or tearing the player down. */
    suspend fun stop() = lock.withLock {
        stopDrain()
        pendingPcm.clear()
        releaseTrack()
        native.drainAudio()
    }

    /**
     * The hardware clock: the AudioTrack playback head, so the UI can follow it instead of guessing
     * from librespot's event cadence.
     */
    fun currentPositionMs(): Long? {
        val output = track ?: return null
        // A release racing this read makes both the head and the sample rate throw; the position is
        // only advisory for the UI, so a lost sample means "no clock right now".
        return runCatching {
            val head = output.playbackHeadPosition.toLong()
            val sampleRate = output.sampleRate
            if (sampleRate <= 0) return@runCatching null
            if (head < lastHeadFrames) headWrapOffset += 1L shl 32
            lastHeadFrames = head
            val frames = head + headWrapOffset - startHeadFrames
            startPositionMs + frames * 1_000L / sampleRate
        }.getOrNull()
    }

    /** Re-bases the playback-head clock after a seek without recreating the AudioTrack. */
    suspend fun seek(positionMs: Long) = lock.withLock {
        val output = track ?: return@withLock
        val wasPlaying = output.playState == AudioTrack.PLAYSTATE_PLAYING
        stopDrain()
        pendingPcm.clear()
        // Discard PCM decoded for the old position and unblock the player so it can process the seek.
        native.drainAudio()
        runCatching { output.pause() }
        runCatching { output.flush() }
        val head = runCatching { output.playbackHeadPosition.toLong() }.getOrDefault(0L)
        startPositionMs = positionMs
        startHeadFrames = head
        lastHeadFrames = head
        headWrapOffset = 0L
        if (wasPlaying) {
            // The flushed track is empty again, so it waits for the new position's audio.
            startDrain(output, startWhenPrefilled = true)
        }
    }

    private suspend fun startDrain(output: AudioTrack, startWhenPrefilled: Boolean) {
        stopDrain()
        // A fresh item and a seek both wait for their first new PCM before they are audible.
        if (startWhenPrefilled) onBuffering(true)
        val prefill = audioTrack.prefillBytes()
        val policy = DrainPolicy(
            prefillBytes = prefill,
            minStartBytes = audioTrack.minStartBytes(prefill),
            prefillTimeoutMs = PREPLAY_TIMEOUT_MS,
            starveTimeoutMs = STARVE_TIMEOUT_MS,
            playingStarveTimeoutMs = PLAYING_STARVE_TIMEOUT_MS
        ).apply { begin(SystemClock.elapsedRealtime(), playing = !startWhenPrefilled) }
        drain = scope.launch(Dispatchers.IO) {
            var reads = 0
            var bytes = 0L
            var buffered = 0L
            var playing = !startWhenPrefilled
            var buffering = startWhenPrefilled
            var underruns = underrunCount(output)
            var lastUnderrunCheck = SystemClock.elapsedRealtime()
            try {
                // Replay PCM captured at pause before reading anything new from the player.
                while (pendingPcm.isNotEmpty()) {
                    val pcm = pendingPcm.removeFirst()
                    write(output, pcm)
                    reads++
                    bytes += pcm.size
                    buffered += pcm.size
                    if (buffering) {
                        buffering = false
                        onBuffering(false)
                    }
                    policy.onData(pcm.size, SystemClock.elapsedRealtime())
                }
                while (isActive) {
                    val pcm = native.readAudio(READ_TIMEOUT_MS)
                    val now = SystemClock.elapsedRealtime()
                    if (pcm != null && pcm.isNotEmpty()) {
                        write(output, pcm)
                        reads++
                        bytes += pcm.size
                        buffered += pcm.size
                        if (buffering) {
                            buffering = false
                            onBuffering(false)
                        }
                        policy.onData(pcm.size, now)
                        if (reads == 1) SidetrackDebug.log { "audio first PCM read bytes=${pcm.size}" }
                        if (reads % 100 == 0) SidetrackDebug.log { "audio drained reads=$reads bytes=$bytes" }
                    }
                    if (!playing && policy.mayPlay(now)) {
                        playing = true
                        runCatching { output.play() }
                        SidetrackDebug.log(
                            "audio.play bufferedBytes=$buffered bufferFrames=${audioTrack.bufferBytes / BYTES_PER_FRAME}"
                        )
                    }
                    if (playing && !buffering && policy.idleFor(now) >= BUFFERING_TIMEOUT_MS) {
                        buffering = true
                        onBuffering(true)
                        SidetrackDebug.log("audio buffering reads=$reads bytes=$bytes")
                    }
                    if (policy.starved(now)) {
                        throw AudioStalledException(
                            "no PCM for ${policy.idleFor(now)}ms (playing=$playing) " +
                                "after $reads reads/$bytes bytes"
                        )
                    }
                    if (now - lastUnderrunCheck >= UNDERRUN_CHECK_MS) {
                        lastUnderrunCheck = now
                        val counted = underrunCount(output)
                        if (counted > underruns) {
                            SidetrackDebug.log("audio underrun underruns=$counted reads=$reads")
                        }
                        underruns = counted
                    }
                }
            } catch (t: Throwable) {
                if (isActive) {
                    SidetrackDebug.log("audio drain failed reads=$reads bytes=$bytes", t)
                    onFailure(t)
                } else {
                    SidetrackDebug.log("audio drain cancelled reads=$reads bytes=$bytes")
                }
            }
        }
    }

    /**
     * Writes the whole buffer without ever blocking inside the HAL.
     *
     * `WRITE_BLOCKING` waits for the track to drain and that wait is not interruptible, so a pause or
     * track change would sit in `cancelAndJoin` until the HAL returned. A full buffer instead means
     * the consumer is the clock, so this sleeps and retries.
     */
    private suspend fun write(output: AudioTrack, pcm: ByteArray) {
        var offset = 0
        while (offset < pcm.size) {
            currentCoroutineContext().ensureActive()
            val written = output.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            if (written < 0) {
                // ERROR_DEAD_OBJECT (route lost) or ERROR_INVALID_OPERATION. The track cannot be
                // reused; the next start builds a new one.
                trackStale = true
                throw IllegalStateException("AudioTrack write failed: $written")
            }
            if (written == 0) {
                delay(WRITE_RETRY_MS)
                continue
            }
            offset += written
        }
    }

    private suspend fun stopDrain() {
        val job = drain ?: return
        drain = null
        job.cancel()
        // The loop checks for cancellation between retries, so this returns in milliseconds; the
        // bound only covers a track that refuses to come back.
        if (withTimeoutOrNull(DRAIN_JOIN_TIMEOUT_MS) { job.join() } == null) {
            SidetrackDebug.log("audio drain ignored cancellation; abandoning track")
            trackStale = true
        }
    }

    private fun capturePendingPcm() {
        while (true) {
            val pcm = native.readAudio(0) ?: break
            if (pcm.isNotEmpty()) pendingPcm.addLast(pcm)
        }
    }

    private fun releaseTrack() {
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.release() }
        }
        track = null
        trackStale = false
        audioTrack.reset()
    }

    /** A track may only be reused for the format it was built for, and only if it still works. */
    private fun canReuse(output: AudioTrack, format: Format): Boolean = !trackStale &&
        runCatching { output.sampleRate == format.sampleRate && output.channelCount == format.channels }
            .getOrDefault(false)

    private fun underrunCount(output: AudioTrack): Int = runCatching { output.underrunCount }.getOrDefault(0)

    private companion object {
        /** How long a read may wait for PCM before the loop re-checks its own state. */
        const val READ_TIMEOUT_MS = 100L

        /** Retry interval for a full AudioTrack buffer. Short enough that cancel stays responsive. */
        const val WRITE_RETRY_MS = 10L

        /** S16LE stereo: 4 bytes per frame. */
        const val BYTES_PER_FRAME = 4

        /** Never keep a track silently unplayed for longer than this waiting for the first PCM. */
        const val PREPLAY_TIMEOUT_MS = 1_500L

        /** No PCM for this long means the speaker has run dry and the UI must stop the clock. */
        const val BUFFERING_TIMEOUT_MS = 1_500L

        /** No PCM at all for this long means the pipeline is dead, not merely buffering. */
        const val STARVE_TIMEOUT_MS = 15_000L

        /**
         * Once audio flows, this much silence is a dead connection: recovering costs a reload, where
         * waiting for 15 s costs the listener the whole gap.
         */
        const val PLAYING_STARVE_TIMEOUT_MS = 3_000L

        /** How often the AudioTrack underrun counter is compared (cheap, but a binder call). */
        const val UNDERRUN_CHECK_MS = 1_000L

        /** How long a cancelled drain may take before its track is abandoned instead. */
        const val DRAIN_JOIN_TIMEOUT_MS = 1_000L
    }
}

/** The native player produced no PCM for long enough that playback must be rebuilt. */
internal class AudioStalledException(message: String) : IllegalStateException(message)
