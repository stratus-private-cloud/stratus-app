package dev.stratus.ui

/**
 * The address that opens a place in the phone's maps app. Nothing is looked up
 * here: the coordinates leave the phone only when somebody taps, and only for
 * the app they chose (stratus-app#215 is about doing better).
 */
internal expect fun mapUri(latitude: Double, longitude: Double): String
