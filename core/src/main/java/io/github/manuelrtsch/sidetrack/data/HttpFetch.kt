package io.github.manuelrtsch.sidetrack.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The timeout a provider call uses unless it asks for its own. */
const val HTTP_TIMEOUT_MS = 15_000

/**
 * One provider HTTP response, before that provider decides what the status means.
 *
 * [body] is whichever stream the status selects — success or error — so a provider can quote the
 * server's own message. Interpreting the status deliberately stays with each provider: a 404 is a
 * missing track to Plex and a different thing to Subsonic, and each keeps its own failure words.
 */
class HttpResponse(
    val status: Int,
    val body: String,
    private val headers: Map<String, List<String>>
) {
    val isSuccess: Boolean get() = status in 200..299

    /** One response header, case-insensitively, or null when the server sent none. */
    fun header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value
        ?.firstOrNull()
}

/**
 * The socket half every provider shares: connect, set headers, write an optional body, read the
 * response text, disconnect.
 *
 * Errors surface as [IOException]; the caller maps them, because each provider's failure
 * vocabulary ([ServiceException], Spotify's gateway exception) belongs to that provider.
 */
suspend fun httpFetch(
    url: String,
    method: String = "GET",
    headers: Map<String, String> = emptyMap(),
    body: String? = null,
    timeoutMs: Int = HTTP_TIMEOUT_MS
): HttpResponse = withContext(Dispatchers.IO) {
    DebugLatency.pause()
    val payload = body?.toByteArray()
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = timeoutMs
        readTimeout = timeoutMs
        headers.forEach { (name, value) -> setRequestProperty(name, value) }
        if (payload != null) doOutput = true
    }
    try {
        if (payload != null) connection.outputStream.use { it.write(payload) }
        val status = connection.responseCode
        val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        HttpResponse(status, text, headersSnapshot(connection))
    } finally {
        connection.disconnect()
    }
}

private fun headersSnapshot(connection: HttpURLConnection): Map<String, List<String>> = connection.headerFields.entries
    .filter { it.key != null }
    .associate { (name, values) -> name to values.toList() }
