package dev.stratus.core.play

import dev.stratus.core.dav.DavResource
import dev.stratus.core.share.ShareLife
import dev.stratus.core.share.ShareLinks

/** Whether there is anything in this file for the player. */
internal fun isTrack(entry: DavResource): Boolean {
    if (entry.isDirectory) return false
    val type = entry.contentType?.substringBefore(';')?.trim()?.lowercase()
    if (type != null && type.startsWith("audio/")) return true
    return entry.name.substringAfterLast('.', "").lowercase() in AUDIO
}

/**
 * [entry] as something the player can fetch on its own: a signed link with a
 * day's life, since an album is listened to in an evening and a link cannot
 * be taken back. Null where it cannot be signed.
 */
internal fun trackOf(links: ShareLinks, entry: DavResource, artwork: ByteArray?, now: Long): Track? {
    // A file under /music/ is signed at its address in the tree, which is
    // the only one the server answers a link at.
    val url = links.link(entry.source ?: entry.path, isDirectory = false, life = ShareLife.ADay, nowEpochSeconds = now)
        ?: return null
    val segments = entry.path.trim('/').split('/')
    // /music/<artist>/<album>/<track>: what the tree says is what the tags
    // said, because the server generated it from them.
    val underMusic = segments.size == 4 && segments[0] == "music"
    return Track(
        url = url,
        // The name without its extension, which is what the server made it
        // from: the track's number and title.
        title = entry.name.substringBeforeLast('.'),
        album = if (underMusic) segments[2] else null,
        artist = if (underMusic) segments[1] else null,
        contentType = entry.contentType,
        artwork = artwork,
    )
}

private val AUDIO = setOf("mp3", "m4a", "flac", "ogg", "opus", "wav", "aac", "alac", "aiff")
