package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.ServiceException
import io.github.manuelrtsch.sidetrack.data.ServiceFailure

/**
 * The one line the user sees when a service call fails.
 *
 * It lives outside the ViewModel because every path that talks to a service needs it — library
 * paging, search, radio, saves and playback — and none of them should phrase a failure their own
 * way.
 *
 * Catalog and playback share the mapping except where the words would be wrong: a page that could
 * not be read has nothing to do with the transport's readiness, so [catalogMessage] does not say
 * "playback" for [ServiceFailure.NotConnected].
 */
internal fun catalogMessage(error: Exception, online: Boolean): String = message(error, online, playback = false)

internal fun serviceMessage(error: Exception, online: Boolean): String = message(error, online, playback = true)

private fun message(error: Exception, online: Boolean, playback: Boolean): String = when ((error as? ServiceException)?.failure) {
    ServiceFailure.Unavailable ->
        if (playback) "Playback unavailable in this build" else "This service isn't available in this build"

    ServiceFailure.NotConnected ->
        when {
            playback -> "Playback isn't ready"
            !online -> "No internet connection"
            else -> "The service isn't connected"
        }

    ServiceFailure.AuthenticationExpired -> "Session expired — sign in again"

    ServiceFailure.Network -> if (!online) "No internet connection" else "Couldn't reach the service"

    ServiceFailure.RateLimited -> "The service is rate-limiting"

    ServiceFailure.TrackUnavailable -> "This track isn't available"

    ServiceFailure.NotAllowed -> "The service won't allow that"

    // Keep the status subtitle to one line; the full exception is already logged by callers.
    ServiceFailure.Unknown, null -> "Something went wrong"
}
