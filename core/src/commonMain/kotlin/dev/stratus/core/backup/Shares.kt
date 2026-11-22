package dev.stratus.core.backup

/**
 * What the system's share sheet hands over (stratus-app#128), the web's
 * `POST /share-target` from the other side (stratus-backend#312), and decided
 * the same way because both answer the same fact: **a share carries no
 * destination.**
 *
 * - It lands under `shared/<year>/<month>/` beside the backup's own folder --
 *   `files/shared/` against a Stratus, the very folder the web writes to -- so
 *   both doors fill one place, and a picker in the middle of the one gesture
 *   this exists to be is friction exactly where somebody wanted none. The month
 *   is UTC's, as the server's is.
 * - It never replaces: it is a hand upload, so a taken name lands as ` (2)`.
 * - One with no name of its own is named for the moment it arrived and the
 *   type it declared, which is everything anybody knows about it.
 */
fun shareFolder(backupRoot: String, at: Long): String {
    val root = backupRoot.trim('/')
    val parent = if ('/' in root) root.substringBeforeLast('/') + "/" else ""
    val t = utcPartsOf(at)
    return "${parent}shared/${t.year.pad(4)}/${t.month.pad(2)}"
}

/** [name] if the share brought a usable one, otherwise the moment it arrived with an extension for [type]. */
fun sharedName(name: String?, type: String?, at: Long): String {
    val given = name?.substringAfterLast('/')?.trim()
    if (!given.isNullOrEmpty() && given != "." && given != "..") return given
    val t = utcPartsOf(at)
    val stamp = "${t.year.pad(4)}-${t.month.pad(2)}-${t.day.pad(2)} " +
        "${t.hour.pad(2)}-${t.minute.pad(2)}-${t.second.pad(2)}"
    return stamp + (type?.substringBefore(';')?.trim()?.lowercase()?.let { SHARE_EXT[it] } ?: "")
}

/**
 * The server's `shareExt`, entry for entry, so a nameless share is called the
 * same thing whichever door it came through.
 */
private val SHARE_EXT = mapOf(
    "image/jpeg" to ".jpg",
    "image/png" to ".png",
    "image/gif" to ".gif",
    "image/webp" to ".webp",
    "image/heic" to ".heic",
    "video/mp4" to ".mp4",
    "video/quicktime" to ".mov",
    "audio/mpeg" to ".mp3",
    "application/pdf" to ".pdf",
    "text/plain" to ".txt",
)

private fun Int.pad(n: Int) = toString().padStart(n, '0')

/** What came of a share, for the platform to say in a line. */
sealed interface ShareOutcome {
    data class Queued(val folder: String, val count: Int) : ShareOutcome

    /** No server to send it to. */
    data object SignedOut : ShareOutcome

    /** A piece of text or a link: this door is for files, as the web's is. */
    data object NotAFile : ShareOutcome
}
