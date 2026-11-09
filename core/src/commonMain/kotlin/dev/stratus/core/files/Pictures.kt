package dev.stratus.core.files

import dev.stratus.core.dav.DavResource

/**
 * The photographs of one folder, opened at one of them.
 *
 * [among] is the folder's pictures in the listing's order, taken when the
 * viewer opened rather than read as it goes, so that a listing refreshed
 * underneath cannot move the photograph somebody is looking at.
 */
data class Viewing(val among: List<DavResource>, val at: Int)

/** What came back when a photograph was asked for in full. */
sealed interface Original {
    class Bytes(val bytes: ByteArray) : Original

    /** Bigger than a phone should hold to draw once; it opens in another app instead. */
    data object TooLarge : Original

    data object Failed : Original
}

/**
 * Whether the viewer draws this file: what both platforms' own decoders read.
 * HEIC is in it because it is what an iPhone records and what the backup
 * uploads untouched; TIFF and camera raw are not, because Android's decoder
 * does not read them, and they still open in another app.
 */
fun isPicture(entry: DavResource): Boolean {
    if (entry.isDirectory) return false
    val type = entry.contentType?.substringBefore(';')?.trim()?.lowercase()
    if (type != null && type in PICTURE_TYPES) return true
    return entry.name.substringAfterLast('.', "").lowercase() in PICTURE_EXTENSIONS
}

/**
 * The original is held whole to be decoded, so there is a ceiling. A phone's
 * own photographs are a few megabytes and a 200-megapixel one is about forty.
 */
const val LARGEST_ORIGINAL = 64L * 1024 * 1024

private val PICTURE_TYPES = setOf(
    "image/jpeg", "image/png", "image/gif", "image/webp", "image/heic", "image/heif", "image/avif", "image/bmp",
)
private val PICTURE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "bmp")
