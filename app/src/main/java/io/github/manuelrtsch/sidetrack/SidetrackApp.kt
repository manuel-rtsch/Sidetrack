package io.github.manuelrtsch.sidetrack

import android.app.Application
import io.github.manuelrtsch.sidetrack.data.DebugLatency

/**
 * Process entry point for diagnostics that must exist before the first Activity or service.
 *
 * The rolling playback log is deliberately installed here: a stall during an early play command
 * or a notification action has to be captured even when no screen was ever opened.
 */
class SidetrackApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Debug and dev builds are the ones used for a field capture, so they keep the file on
        // without the log tag. A release build still opts in with `log.tag.SidetrackDebug DEBUG`.
        SidetrackDebug.installFileLog(
            this,
            always = BuildConfig.BUILD_TYPE == "debug" || BuildConfig.BUILD_TYPE == "dev"
        )
        // Only the debug build honours `-PsidetrackLatencyMs`; `dev`, `profile`, `demo` and
        // release are not `DEBUG`, so their network calls cannot be slowed by a stray flag.
        if (BuildConfig.DEBUG) {
            DebugLatency.millis = BuildConfig.NETWORK_DELAY_MS
            if (DebugLatency.millis > 0) {
                SidetrackDebug.log("network delay: ${DebugLatency.millis} ms before every provider call")
            }
        }
        StartupTrace.mark("application created")
    }
}
