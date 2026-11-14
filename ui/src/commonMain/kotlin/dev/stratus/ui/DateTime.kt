package dev.stratus.ui

/** A moment as the phone's own locale and zone write one, date and time. */
internal expect fun dateTime(epochMs: Long): String

/**
 * A camera's clock, `2024-06-15T18:30:05`, in the phone's own way of writing
 * one but in no zone: the camera recorded none, so converting it would move a
 * photograph to whatever time it was here. Null for anything else.
 */
internal expect fun wallClock(taken: String): String?
