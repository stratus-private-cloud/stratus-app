package dev.stratus.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceCreateThumbnailWithTransform
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize

// Skia is what Compose draws with here, so it is already in the binary.
internal actual fun decodePicture(bytes: ByteArray): ImageBitmap? =
    try {
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    } catch (_: Exception) {
        null
    }

// ImageIO and not Skia: Skia reads no HEIC. A "thumbnail" at a maximum size is
// ImageIO's one call that subsamples while decoding and applies the EXIF
// orientation, and the pixels are then drawn into a buffer Skia can wrap.
@OptIn(ExperimentalForeignApi::class)
internal actual fun decodeOriginal(bytes: ByteArray, longestSide: Int): ImageBitmap? = memScoped {
    if (bytes.isEmpty()) return@memScoped null
    val data = bytes.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), bytes.size.convert()) }
        ?: return@memScoped null
    defer { CFRelease(data) }
    val source = CGImageSourceCreateWithData(data, null) ?: return@memScoped null
    defer { CFRelease(source) }

    val side = alloc<IntVar> { value = longestSide }
    val number = CFNumberCreate(null, kCFNumberIntType, side.ptr) ?: return@memScoped null
    defer { CFRelease(number) }
    val options = CFDictionaryCreateMutable(
        null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
    ) ?: return@memScoped null
    defer { CFRelease(options) }
    CFDictionarySetValue(options, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
    CFDictionarySetValue(options, kCGImageSourceCreateThumbnailWithTransform, kCFBooleanTrue)
    CFDictionarySetValue(options, kCGImageSourceThumbnailMaxPixelSize, number)

    val image = CGImageSourceCreateThumbnailAtIndex(source, 0uL, options) ?: return@memScoped null
    defer { CGImageRelease(image) }
    val width = CGImageGetWidth(image).toInt()
    val height = CGImageGetHeight(image).toInt()
    if (width <= 0 || height <= 0) return@memScoped null

    val rowBytes = width * 4
    val pixels = ByteArray(rowBytes * height)
    val drawn = pixels.usePinned { pinned ->
        val space = CGColorSpaceCreateDeviceRGB()
        val context = CGBitmapContextCreate(
            pinned.addressOf(0),
            width.convert(),
            height.convert(),
            8uL,
            rowBytes.convert(),
            space,
            CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
        )
        CGColorSpaceRelease(space)
        if (context == null) return@usePinned false
        CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), image)
        CGContextRelease(context)
        true
    }
    if (!drawn) return@memScoped null
    Image.makeRaster(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL), pixels, rowBytes)
        .toComposeImageBitmap()
}
