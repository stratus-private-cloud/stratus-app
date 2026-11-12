package dev.stratus.ui

import java.text.DateFormat
import java.util.Date

internal actual fun dateTime(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))
