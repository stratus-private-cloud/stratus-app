package dev.stratus.core.backup

import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Where a queued row's bytes are (stratus-app#127).
 *
 * A column rather than a guess from the shape of `local_id`: a content URI and
 * a MediaStore id look different today, and a rule that relied on it would be
 * wrong once and then permanent.
 */
enum class Origin {
    /** The camera roll, read through [AssetSource]. */
    Library,

    /** A file somebody picked to upload by hand, read through [PickedFiles]. */
    Picked,
}

/** A file somebody picked, as the platform describes it once it can be read again later. */
data class PickedFile(
    /** What [PickedFiles.open] takes: a content URI on Android, the copy's name on iOS. */
    val id: String,
    val name: String,
    val size: Long,
    val contentType: String?,
)

/**
 * The bytes of files picked by hand, [AssetSource]'s twin for what is not in
 * the photo library (stratus-app#127).
 *
 * Whatever [id][PickedFile.id] names has to survive the process dying, since a
 * hand upload resumes like any other row: Android keeps a persistable grant on
 * the URI, and iOS copies the file into the app's own container, because a
 * security-scoped URL does not outlive a relaunch.
 */
interface PickedFiles {
    /** The bytes from [from] onwards, skipped by the platform rather than read and thrown away where it can. */
    suspend fun open(id: String, from: Long = 0): RawSource

    /** The same bytes at [toPath], for a transport that takes a file. */
    suspend fun writeTo(id: String, from: Long, toPath: String) {
        open(id, from).use { source ->
            SystemFileSystem.sink(Path(toPath)).buffered().use { sink -> sink.transferFrom(source) }
        }
    }

    /** Lets go of [id] once nothing will read it again: the grant on Android, the copy on iOS. */
    suspend fun forget(id: String) {}

    /**
     * What the share sheet left for the app to take in, already moved where
     * [open] reads it (stratus-app#128). Only iOS has any: its Share
     * Extension is another process, so it leaves the files in the App Group
     * and the next pass queues them. Android's share arrives in this process.
     */
    suspend fun shared(): List<PickedFile> = emptyList()
}

/**
 * [name] with a number before its extension, `scan.pdf` to `scan (2).pdf`, and
 * [n] of 1 the name itself.
 *
 * The server's own `db.CopyName`, extension rule included -- Go's `path.Ext`,
 * the last dot of the last segment -- so a file uploaded by hand is numbered
 * the way the import folder and a restore number theirs.
 */
fun copyName(name: String, n: Int): String {
    if (n <= 1) return name
    val ext = extensionOf(name)
    return name.removeSuffix(ext) + " ($n)" + ext
}

/** What [copyName] was given, from what it answered for [n]. */
internal fun originalName(copy: String, n: Int): String {
    if (n <= 1) return copy
    val ext = extensionOf(copy)
    return copy.removeSuffix(ext).removeSuffix(" ($n)") + ext
}

private fun extensionOf(path: String): String {
    for (i in path.indices.reversed()) {
        when (path[i]) {
            '/' -> return ""
            '.' -> return path.substring(i)
        }
    }
    return ""
}

/** Past this, a name is a situation to be told about rather than added to -- the server's `MaxCopies`. */
internal const val MOST_COPIES = 100
