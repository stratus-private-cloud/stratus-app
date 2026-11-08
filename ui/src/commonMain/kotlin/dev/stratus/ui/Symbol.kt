package dev.stratus.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** The subset as a family each platform can draw, from the font's bytes. */
internal expect fun symbolsFamily(bytes: ByteArray): FontFamily

@OptIn(ExperimentalEncodingApi::class)
private val symbols: FontFamily by lazy { symbolsFamily(Base64.decode(SYMBOLS_FONT)) }

/** A [Glyph], in the theme's colour unless told otherwise, at icon size by default. */
@Composable
fun Symbol(
    glyph: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current.copy(fontSize = 24.sp),
    color: Color = Color.Unspecified,
) {
    Text(glyph, modifier, color = color, style = style.copy(fontFamily = symbols, lineHeight = style.fontSize))
}
