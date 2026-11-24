package io.github.manuelrtsch.sidetrack.playback

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState as SessionState
import android.os.SystemClock
import io.github.manuelrtsch.sidetrack.MediaItem
import io.github.manuelrtsch.sidetrack.SidetrackDebug

/**
 * Owns the platform [MediaSession]: it is created on first use, its transport callbacks are routed
 * to the controller, and its playback state and metadata are kept in step with the player.
 *
 * Metadata is throttled because a session update is a binder call and the position changes far
 * more often than the track does; artwork only goes out with the item it belongs to.
 */
internal class PlaybackSessionBinder(
    private val context: Context?,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onSeekTo: (Long) -> Unit,
    private val onStop: () -> Unit
) {
    private var session: MediaSession? = null
    private var lastMetadataAt = 0L
    private var lastStateAt = 0L
    private var itemPresent = false

    /** Queue ids already written to the session, so a settled queue is not re-sent. */
    private var queueIds: List<Long> = emptyList()
    private var activeQueueId: Long = SessionState.PLAYBACK_POSITION_UNKNOWN

    val token: MediaSession.Token? get() = session()?.sessionToken

    fun publish(item: MediaItem?, playing: Boolean, positionMs: Long, art: Bitmap?) {
        val session = session() ?: return
        val now = SystemClock.elapsedRealtime()
        if (item != null) updateMetadata(session, item, art, now)
        writeState(session, item != null, playing, positionMs, now)
    }

    /**
     * Advances the session's position without re-sending metadata. Throttled: the UI clock ticks
     * several times a second, and each write is a binder call.
     */
    fun publishPosition(positionMs: Long, playing: Boolean) {
        val session = session() ?: return
        val now = SystemClock.elapsedRealtime()
        if (!playing || now - lastStateAt < POSITION_INTERVAL_MS) return
        writeState(session, itemPresent, playing, positionMs, now)
    }

    /**
     * Publishes the queue shown to the platform, with [current] as the active item. Returns true
     * when the queue itself changed, so the caller knows a state write is worth making.
     */
    fun setQueue(items: List<MediaItem>, current: MediaItem?): Boolean {
        val session = session() ?: return false
        val ids = items.map { queueItemId(it) }
        val changed = ids != queueIds
        if (changed) {
            queueIds = ids
            session.setQueue(
                items.mapIndexed { index, item ->
                    MediaSession.QueueItem(
                        MediaDescription.Builder()
                            .setMediaId(item.key.encode())
                            .setTitle(item.title)
                            .setSubtitle(item.creator)
                            .build(),
                        ids[index]
                    )
                }
            )
        }
        activeQueueId = current?.let { item ->
            ids.getOrNull(items.indexOfFirst { it.key == item.key })
        } ?: SessionState.PLAYBACK_POSITION_UNKNOWN
        return changed
    }

    private fun writeState(
        session: MediaSession,
        hasItem: Boolean,
        playing: Boolean,
        positionMs: Long,
        now: Long
    ) {
        itemPresent = hasItem
        lastStateAt = now
        session.setPlaybackState(
            SessionState.Builder()
                .setActions(
                    SessionState.ACTION_PLAY or SessionState.ACTION_PAUSE or
                        SessionState.ACTION_PLAY_PAUSE or SessionState.ACTION_SKIP_TO_NEXT or
                        SessionState.ACTION_SKIP_TO_PREVIOUS or SessionState.ACTION_SEEK_TO or
                        SessionState.ACTION_STOP
                )
                .setActiveQueueItemId(activeQueueId)
                .setState(
                    when {
                        !hasItem -> SessionState.STATE_STOPPED
                        playing -> SessionState.STATE_PLAYING
                        else -> SessionState.STATE_PAUSED
                    },
                    positionMs,
                    if (playing) 1f else 0f,
                    now
                )
                .build()
        )
    }

    private fun queueItemId(item: MediaItem): Long = item.key.encode().hashCode().toLong()

    /** Writes the metadata now, unless the throttle window has not elapsed yet. */
    fun updateMetadata(item: MediaItem?, art: Bitmap?) {
        val session = session() ?: return
        item ?: return
        updateMetadata(session, item, art, SystemClock.elapsedRealtime())
    }

    /** Lets the next update carry new artwork even if the throttle window is still open. */
    fun clearMetadataThrottle() {
        lastMetadataAt = 0L
    }

    /** Marks the session stopped while keeping it alive for the next item. */
    fun markStopped() {
        session?.setPlaybackState(
            SessionState.Builder()
                .setState(SessionState.STATE_STOPPED, 0L, 0f, SystemClock.elapsedRealtime())
                .build()
        )
    }

    fun release() {
        session?.release()
        session = null
    }

    private fun updateMetadata(session: MediaSession, item: MediaItem, art: Bitmap?, now: Long) {
        if (lastMetadataAt != 0L && now - lastMetadataAt < METADATA_INTERVAL_MS) return
        lastMetadataAt = now
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, item.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, item.creator)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, item.creator)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, item.durationMs)
                .apply { art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build()
        )
    }

    private fun session(): MediaSession? {
        session?.let { return it }
        val context = context ?: return null
        val created = MediaSession(context, "SideKit Sidetrack").apply {
            // Without this the platform reports CONTENT_TYPE_UNKNOWN, and audio focus on some
            // ROMs then ducks the wrong way for music.
            setPlaybackToLocal(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() {
                        command("play")
                        this@PlaybackSessionBinder.onPlay()
                    }

                    override fun onPause() {
                        command("pause")
                        this@PlaybackSessionBinder.onPause()
                    }

                    override fun onSkipToNext() {
                        command("next")
                        this@PlaybackSessionBinder.onNext()
                    }

                    override fun onSkipToPrevious() {
                        command("previous")
                        this@PlaybackSessionBinder.onPrevious()
                    }

                    override fun onSeekTo(pos: Long) {
                        command("seekTo $pos")
                        this@PlaybackSessionBinder.onSeekTo(pos)
                    }

                    override fun onStop() {
                        command("stop")
                        this@PlaybackSessionBinder.onStop()
                    }
                }
            )
            isActive = true
        }
        session = created
        return created
    }

    /**
     * Records that the platform asked for a transport command, saying nothing about who asked.
     *
     * A notification button, a Bluetooth media key and another app's controller all arrive here,
     * and the rest of the playback log cannot tell any of them from a keypad press: a pause that
     * names no origin is why a whole incident had to be reconstructed from `dumpsys audio`.
     */
    private fun command(name: String) = SidetrackDebug.log("session command: $name")

    private companion object {
        /** Minimum gap between metadata writes; the position is published separately. */
        const val METADATA_INTERVAL_MS = 250L

        /** Minimum gap between position-only state writes, which the platform extrapolates. */
        const val POSITION_INTERVAL_MS = 1_000L
    }
}
