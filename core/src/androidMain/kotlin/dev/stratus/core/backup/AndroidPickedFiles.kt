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
import java.io.File
import java.io.FileInputStream
import java.util.UUID

/**
 * Files picked with the system's own picker, read through `ContentResolver`
 * (stratus-app#127).
 *
 * The id is the document's URI, and what keeps it readable after the process
 * dies is a **persistable** grant taken when it is picked: an ordinary one ends
 * with the activity, and a hand upload resumes like any other row, possibly a
 * day later from a worker with no activity at all.
 *
 * **A share has no such grant to take** (stratus-app#128): what comes with
 * `ACTION_SEND` ends with the activity it was sent to, so [keep] copies the
 * bytes into the app's own storage first, and the id says which it is.
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

    /**
     * What was shared, copied where it outlives the share: under `shares/` in
     * the app's files, which nothing purges behind a pass's back. The name is
     * the one the sender gave, or empty for [sharedName] to fill in.
     */
    suspend fun keep(uris: List<Uri>): List<PickedFile> = withContext(Dispatchers.IO) {
        uris.mapNotNull { uri ->
            val id = UUID.randomUUID().toString()
            val copy = File(shares(), id)
            try {
                val stream = resolver.openInputStream(uri) ?: return@mapNotNull null
                stream.use { input -> copy.outputStream().use { input.copyTo(it) } }
            } catch (_: Exception) {
                copy.delete()
                return@mapNotNull null
            }
            var name = ""
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0)
            }
            PickedFile(id = SHARED + id, name = name, size = copy.length(), contentType = resolver.getType(uri))
        }
    }

    private fun shares() = File(context.filesDir, "shares").apply { mkdirs() }

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
        if (id.startsWith(SHARED)) {
            val stream = FileInputStream(File(shares(), id.removePrefix(SHARED)))
            stream.channel.position(from)
            return@withContext stream.asSource()
        }
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
            if (id.startsWith(SHARED)) {
                File(shares(), id.removePrefix(SHARED)).delete()
                return@withContext
            }
            try {
                resolver.releasePersistableUriPermission(Uri.parse(id), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
        }
    }

    private companion object {
        /** An id that is a copy of ours rather than somebody else's URI, which never starts this way. */
        const val SHARED = "shared:"
    }
}
