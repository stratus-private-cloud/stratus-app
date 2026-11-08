package dev.stratus.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import java.io.File

// Android reads a font from a file, an asset or a resource and never from
// bytes. java.io.tmpdir is the app's cache directory there, which needs no
// Context to reach.
internal actual fun symbolsFamily(bytes: ByteArray): FontFamily {
    val file = File(System.getProperty("java.io.tmpdir"), "stratus-symbols.ttf")
    if (!file.exists() || !file.readBytes().contentEquals(bytes)) file.writeBytes(bytes)
    return FontFamily(Font(file))
}
