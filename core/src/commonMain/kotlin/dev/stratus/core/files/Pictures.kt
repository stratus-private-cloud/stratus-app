package dev.stratus.core.files

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.stratus.core.dav.DavResource

/**
 * The photographs of one folder, opened at one of them.
 *
 * [among] is the folder's pictures in the listing's order, taken when the
 * viewer opened rather than read as it goes, so that a listing refreshed
 * underneath cannot move the photograph somebody is looking at.
 */
data class Viewing(val among: List<DavResource>, val at: Int) {
    /**
     * The same pictures with [gone] taken out, opened at the one that took its
     * place -- or the one before, at the end -- and null when none is left.
     */
    fun without(gone: DavResource): Viewing? {
        val removed = among.indexOfFirst { it.path == gone.path }
        if (removed < 0) return this
        val rest = among.filterIndexed { i, _ -> i != removed }
        if (rest.isEmpty()) return null
        return Viewing(rest, removed.coerceAtMost(rest.lastIndex))
    }
}

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

/**
 * The last originals the viewer downloaded, kept so that swiping back is not a
 * second download of the same sixty megabytes (stratus-app#238). Bounded in
 * bytes rather than in pictures, since one original is a megabyte and another
 * is fifty; keyed by the ETag as well as the path, so a file changed on the
 * server is fetched again.
 */
class KeptOriginals(private val most: Long = KEPT_ORIGINALS) {
    private val lock = Mutex()
    private val kept = LinkedHashMap<String, ByteArray>()
    private var bytes = 0L

    suspend fun get(key: String): ByteArray? = lock.withLock {
        kept.remove(key)?.also { kept[key] = it }
    }

    suspend fun put(key: String, value: ByteArray) = lock.withLock {
        kept.remove(key)?.let { bytes -= it.size }
        kept[key] = value
        bytes += value.size
        val oldest = kept.keys.iterator()
        while (bytes > most && oldest.hasNext()) {
            val key2 = oldest.next()
            if (key2 == key) break
            bytes -= kept.getValue(key2).size
            oldest.remove()
        }
    }

    suspend fun clear() = lock.withLock {
        kept.clear()
        bytes = 0
    }
}

/** Twice the largest original the viewer will hold, so the one either side survives. */
const val KEPT_ORIGINALS = 2 * LARGEST_ORIGINAL
