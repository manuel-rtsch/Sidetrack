package io.github.manuelrtsch.sidetrack.data

import kotlinx.coroutines.delay

/**
 * A debug-build-only pause in front of every provider call, so a loading state can be watched for
 * a few seconds instead of a flash.
 *
 * The app sets [millis] from a Gradle property, and only while `BuildConfig.DEBUG` is true, so a
 * release build cannot be slow: it keeps the default 0 and [pause] costs a volatile read and a
 * branch.
 */
object DebugLatency {
    /** How long each HTTP call waits first. 0 disables the pause. */
    @Volatile
    var millis: Long = 0

    suspend fun pause() {
        if (millis > 0) delay(millis)
    }
}
