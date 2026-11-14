package dev.stratus.ui

import java.text.DateFormat
import java.util.Date

internal actual fun dateTime(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))

internal actual fun wallClock(taken: String): String? {
    val utc = java.util.TimeZone.getTimeZone("UTC")
    val parsed = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ROOT).apply { timeZone = utc }.parse(taken)
    }.getOrNull() ?: return null
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).apply { timeZone = utc }.format(parsed)
}
