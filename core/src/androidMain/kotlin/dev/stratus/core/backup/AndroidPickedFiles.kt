package dev.stratus.core.backup

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.RawSource
import kotlinx.io.asSource

/**
 * Files picked with the system's own picker, read through `ContentResolver`
 * (stratus-app#127).
 *
 * The id is the document's URI, and what keeps it readable after the process
 * dies is a **persistable** grant taken when it is picked: an ordinary one ends
 * with the activity, and a hand upload resumes like any other row, possibly a
 * day later from a worker with no activity at all.
 */
class AndroidPickedFiles(private val context: Context) : PickedFiles {

    private val resolver: ContentResolver get() = context.contentResolver

    /**
     * What the picker handed back, described and kept: the grant taken, the
     * name and the size read. A provider that offers no persistable grant is
     * still described, and readable for as long as this process lives.
     */
    suspend fun describe(uris: List<Uri>): List<PickedFile> = withContext(Dispatchers.IO) {
        uris.mapNotNull { uri ->
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
            var name: String? = null
            var size: Long? = null
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        if (!cursor.isNull(0)) name = cursor.getString(0)
                        if (!cursor.isNull(1)) size = cursor.getLong(1)
                    }
                }
            // The size is not cosmetic: it is the Content-Length of a PUT and
            // the Upload-Length tus declares, so a provider that does not say
            // is measured rather than guessed.
            val bytes = size ?: lengthOf(uri) ?: return@mapNotNull null
            PickedFile(
                id = uri.toString(),
                name = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file",
                size = bytes,
                contentType = resolver.getType(uri),
            )
        }
    }

    private fun lengthOf(uri: Uri): Long? {
        resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            if (descriptor.length != AssetFileDescriptor.UNKNOWN_LENGTH) return descriptor.length
        }
        return resolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
            }
            total
        }
    }

    override suspend fun open(id: String, from: Long): RawSource = withContext(Dispatchers.IO) {
        val stream = requireNotNull(resolver.openInputStream(Uri.parse(id))) { "the picked file would not open" }
        // Skipped where the stream can, and read and thrown away where it
        // cannot: a provider's stream may skip nothing, and stopping there
        // would send the start of the file as its middle.
        var left = from
        val buffer = ByteArray(64 * 1024)
        while (left > 0) {
            val skipped = stream.skip(left)
            if (skipped > 0) {
                left -= skipped
                continue
            }
            val read = stream.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (read < 0) {
                stream.close()
                error("the picked file is shorter than $from bytes")
            }
            left -= read
        }
        stream.asSource()
    }

    override suspend fun forget(id: String) {
        withContext(Dispatchers.IO) {
            try {
                resolver.releasePersistableUriPermission(Uri.parse(id), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
        }
    }
}
