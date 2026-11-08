package dev.stratus.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font

internal actual fun symbolsFamily(bytes: ByteArray): FontFamily =
    FontFamily(Font(identity = "MaterialSymbols", data = bytes))
