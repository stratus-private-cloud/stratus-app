package dev.stratus.ui

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.nio.ByteBuffer
import kotlin.math.roundToInt

internal actual fun decodePicture(bytes: ByteArray): ImageBitmap? =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()

// ImageDecoder rather than BitmapFactory: it reads HEIF and applies the EXIF
// orientation, and a target size makes it subsample while decoding instead of
// after.
internal actual fun decodeOriginal(bytes: ByteArray, longestSide: Int): ImageBitmap? = try {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        val width = info.size.width
        val height = info.size.height
        val scale = longestSide.toFloat() / maxOf(width, height)
        if (scale < 1f) {
            decoder.setTargetSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
        }
    }.asImageBitmap()
} catch (_: Exception) {
    null
}
