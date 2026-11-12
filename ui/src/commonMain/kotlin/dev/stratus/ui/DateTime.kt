package dev.stratus.ui

/** A moment as the phone's own locale and zone write one, date and time. */
internal expect fun dateTime(epochMs: Long): String
