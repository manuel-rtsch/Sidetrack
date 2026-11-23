package io.github.manuelrtsch.sidetrack.test

import io.github.manuelrtsch.sidetrack.playback.Media3Player
import io.github.manuelrtsch.sidetrack.playback.Media3PlayerListener
import io.github.manuelrtsch.sidetrack.playback.Media3PlayerStatus
import io.github.manuelrtsch.sidetrack.playback.PlaybackSource

/**
 * A player that never produces audio or events: it accepts, it stops, it seeks, and it reports
 * itself idle. Tests of a controller or a runtime that never prepare a track use it as the engine's
 * factory, and extend it when they need one command to answer differently.
 */
open class SilentMedia3Player : Media3Player {
    override var listener: Media3PlayerListener? = null

    override fun prepare(source: PlaybackSource, startPositionMs: Long, playWhenReady: Boolean) = Unit

    override fun play() = Unit

    override fun pause() = Unit

    override fun stop() = Unit

    override fun seekTo(positionMs: Long) = Unit

    override fun release() = Unit

    override val isPlaying: Boolean = false
    override val currentPositionMs: Long = 0L
    override val status: Media3PlayerStatus = Media3PlayerStatus.Idle
}
