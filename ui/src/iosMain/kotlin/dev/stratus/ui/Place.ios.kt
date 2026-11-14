package dev.stratus.ui

// iOS has no `geo:`; Apple Maps answers its own address with a pin.
internal actual fun mapUri(latitude: Double, longitude: Double): String = "https://maps.apple.com/?ll=$latitude,$longitude&q=$latitude,$longitude"
