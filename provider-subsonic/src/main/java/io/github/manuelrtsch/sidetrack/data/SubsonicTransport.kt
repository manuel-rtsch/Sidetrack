package io.github.manuelrtsch.sidetrack.data

import android.util.Log
import java.io.IOException
import org.json.JSONObject

/** One HTTP call, kept as a seam so the catalog and auth tests need no real server. */
fun interface SubsonicTransport {
    suspend fun get(url: String): JSONObject
}

/** The real transport; all parsing stays in [SubsonicApi]. */
class HttpSubsonicTransport : SubsonicTransport {
    override suspend fun get(url: String): JSONObject {
        val response = try {
            httpFetch(url, headers = mapOf("Accept" to "application/json"))
        } catch (e: IOException) {
            throw ServiceException(ServiceFailure.Network, "Couldn't reach the server", e)
        }
        if (!response.isSuccess) {
            Log.w("SidetrackSubsonic", "HTTP ${response.status} ${url.substringBefore('?')}")
            throw ServiceException(ServiceFailure.Network, "The server returned HTTP ${response.status}")
        }
        return runCatching { JSONObject(response.body) }
            .getOrElse { throw ServiceException(ServiceFailure.Unknown, "The server returned an unreadable response", it) }
    }
}
