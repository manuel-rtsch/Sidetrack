package io.github.manuelrtsch.sidetrack.playback

/**
 * Timing rules for one [android.media.AudioTrack] drain: when a freshly created track may start
 * playing, and when a running one has gone silent for long enough to call it dead.
 *
 * Kept away from the audio sink so the rules can be exercised without an AudioTrack, a
 * device or a real clock. Every timestamp is `SystemClock.elapsedRealtime()` milliseconds.
 *
 * Two rules exist because a drain and an idle channel look alike:
 *  - Starting a track before it holds PCM underruns once the first buffer takes longer than the
 *    buffer length to arrive; Android reports "disabled due to previous underrun, restarting"
 *    and the listener hears a gap at the start of a track. The deadline only gives up on the
 *    full prefill, never on having something in hand.
 *  - A drain that polls an empty native channel forever looks exactly like playback to the UI
 *    while it is silent, so the silence needs a deadline.
 */
class DrainPolicy(
    /** Bytes to buffer before playback starts; half the AudioTrack buffer in practice. */
    private val prefillBytes: Int,
    /** The smallest cushion a track may ever start with once the prefill deadline expired. */
    private val minStartBytes: Int,
    /** How long to wait for that prefill before starting anyway. */
    private val prefillTimeoutMs: Long,
    /** How long a drain that never produced PCM may wait before it counts as starved. */
    private val starveTimeoutMs: Long,
    /**
     * How long a drain that was already playing may go silent before it counts as starved.
     *
     * Much shorter than [starveTimeoutMs]: once audio has flowed, a multi-second hole is a dead
     * access point, not a slow first fetch.
     */
    private val playingStarveTimeoutMs: Long = starveTimeoutMs
) {
    private var startedAtMs = 0L
    private var bufferedBytes = 0L
    private var lastDataAtMs = 0L
    private var playing = false

    /** Starts a new track. [playing] is true when the caller already started the AudioTrack. */
    fun begin(nowMs: Long, playing: Boolean) {
        startedAtMs = nowMs
        lastDataAtMs = nowMs
        bufferedBytes = 0L
        this.playing = playing
    }

    fun onData(bytes: Int, nowMs: Long) {
        bufferedBytes += bytes
        lastDataAtMs = nowMs
    }

    /**
     * True exactly once: when the buffered audio reached the prefill target, or the prefill
     * deadline expired with at least [minStartBytes] to play.
     *
     * The deadline only relaxes the target down to [minStartBytes] and no further: starting an
     * empty track underruns it dead, so a source that delivers nothing is caught by [starved].
     */
    fun mayPlay(nowMs: Long): Boolean {
        if (playing) return false
        if (bufferedBytes >= prefillBytes) {
            playing = true
            return true
        }
        if (nowMs - startedAtMs < prefillTimeoutMs) return false
        if (bufferedBytes < minStartBytes) return false
        playing = true
        return true
    }

    fun idleFor(nowMs: Long): Long = nowMs - lastDataAtMs

    /**
     * True once the drain has received no PCM for the whole stall budget, playing or not: a track
     * that never gets enough to start is the same silence from the listener's side, so it must
     * not be waited for forever.
     */
    fun starved(nowMs: Long): Boolean {
        val budget = if (playing) playingStarveTimeoutMs else starveTimeoutMs
        return nowMs - lastDataAtMs >= budget
    }
}
