package dev.stratus.core.calendars

import dev.stratus.core.net.Credentials
import io.ktor.http.Url

/**
 * Calendars and contacts on this phone, which the app does not sync
 * (stratus-app#185): it hands the server to what already does. DAVx5 on
 * Android, and on iOS the system's own accounts -- which an app cannot add, so
 * what it can do is open the page a Stratus serves a profile from
 * (stratus-backend#485).
 */
sealed interface CalendarSetup {
    /** DAVx5's login, with everything filled in. */
    data class Davx5(val uri: String, val credentials: Credentials) : CalendarSetup

    /** The server's own page for it, opened in the browser. */
    data class Page(val url: String) : CalendarSetup
}

/** Where a [CalendarSetup] goes, which is the platform's to know. */
interface CalendarHandoff {
    /** Whether this platform hands over to DAVx5, rather than to a page. */
    val toDavx5: Boolean

    suspend fun open(setup: CalendarSetup)
}

/**
 * What to hand over, or null when there is nothing worth offering: a page is
 * only a Stratus's at its origin, while DAVx5 takes any server.
 */
fun calendarSetup(
    baseUrl: String,
    credentials: Credentials,
    toDavx5: Boolean,
    stratusAtOrigin: Boolean,
): CalendarSetup? = when {
    toDavx5 -> CalendarSetup.Davx5(davx5Uri(baseUrl), credentials)
    stratusAtOrigin -> CalendarSetup.Page(originOf(baseUrl) + CONNECT_PATH)
    else -> null
}

/**
 * The address as DAVx5 opens it: `davx5://` is its own name for https and
 * `caldav://` for http, and it discovers both services from whatever path is
 * there. No credentials in it -- they travel as extras to DAVx5 alone.
 */
fun davx5Uri(baseUrl: String): String {
    val url = Url(baseUrl)
    val scheme = if (url.protocol.name == "https") "davx5" else "caldav"
    return "$scheme://${hostPort(url)}${url.encodedPath.ifEmpty { "/" }}"
}

private fun originOf(baseUrl: String): String {
    val url = Url(baseUrl)
    return "${url.protocol.name}://${hostPort(url)}"
}

private fun hostPort(url: Url): String {
    val host = if (':' in url.host) "[${url.host}]" else url.host
    return if (url.port == url.protocol.defaultPort) host else "$host:${url.port}"
}

/** stratus-backend's page that sets a phone up. */
const val CONNECT_PATH = "/connect"
