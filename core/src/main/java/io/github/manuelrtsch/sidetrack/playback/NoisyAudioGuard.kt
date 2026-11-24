package io.github.manuelrtsch.sidetrack.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import io.github.manuelrtsch.sidetrack.SidetrackDebug

/**
 * Pauses playback when the output route disappears, so unplugging headphones never plays out
 * loud. The receiver is only registered while the player is actually playing.
 */
internal class NoisyAudioGuard(
    private val context: Context?,
    private val onNoisy: () -> Unit
) {
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // The pause this causes names no origin of its own: without this line an output route
            // that vanished looks exactly like a transport button nobody admits to pressing.
            SidetrackDebug.log("noisy: ${intent.action}")
            onNoisy()
        }
    }

    fun update(playing: Boolean) {
        val context = context ?: return
        if (playing == registered) return
        registered = playing
        if (playing) {
            context.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        } else {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}
