package io.github.manuelrtsch.sidetrack.data

import java.net.URL

/**
 * Trims a typed server address into the base URL a provider expects: a missing scheme becomes
 * `http://`, a trailing slash is dropped, and anything that is not an `http(s)` host is refused with
 * [invalidMessage].
 *
 * [defaultPort] fills in the port a default install listens on, but only for a bare `http` address.
 * An `https://` address keeps its implicit 443, because a reverse proxy usually answers there, and an
 * explicit port is already what the user meant. The port goes at the end of the authority so a typed
 * base path survives: a proxied server can sit under one, and that path belongs after the port.
 */
fun normalizeServerUrl(value: String, invalidMessage: String, defaultPort: Int? = null): String {
    val trimmed = value.trim().trimEnd('/')
    val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
    val parsed = runCatching { URL(withScheme) }.getOrNull()
    if (parsed == null || parsed.host.isNullOrEmpty() || (parsed.protocol != "http" && parsed.protocol != "https")) {
        throw ServiceException(ServiceFailure.NotConnected, invalidMessage)
    }
    if (defaultPort == null || parsed.protocol != "http" || parsed.port != -1) return withScheme
    val authorityStart = withScheme.indexOf("://") + 3
    val authorityEnd = withScheme.indexOf('/', authorityStart).let { if (it == -1) withScheme.length else it }
    return withScheme.substring(0, authorityEnd) + ":" + defaultPort + withScheme.substring(authorityEnd)
}
