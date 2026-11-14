package dev.stratus.ui

// `geo:` is what any maps app on Android answers; the q= puts a pin there
// rather than only centring the map.
internal actual fun mapUri(latitude: Double, longitude: Double): String = "geo:$latitude,$longitude?q=$latitude,$longitude"
