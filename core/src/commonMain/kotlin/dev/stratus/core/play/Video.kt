package dev.stratus.core.play

import dev.stratus.core.dav.DavResource
import dev.stratus.core.net.TrustPolicy
import dev.stratus.core.share.ShareLife
import dev.stratus.core.share.ShareLinks
import io.ktor.util.date.getTimeMillis

/**
 * A film for the player: the URL to try, and the one to fall back to when
 * this phone cannot decode what it was given.
 */
data class VideoSource(
    val url: String,
    val title: String,
    /** The server's HLS of the same film, where [url] is the file itself. */
    val fallback: String? = null,
)

/** The platform's full-screen player, which takes over the screen until it is closed. */
fun interface VideoScreen {
    fun show(source: VideoSource, trust: TrustPolicy)
}

/**
 * Which URL a film is played from.
 *
 * **What a phone records is played as it is**: MP4 and QuickTime are what
 * both platforms' players were built for, a range request is all a seek
 * costs, and the server does no work at all. **Everything else is the
 * server's HLS** -- Matroska, AC-3 or DTS sound -- which copies the picture
 * across and re-encodes only what the device could not decode. A file played
 * as it is keeps that HLS as its fallback, for the codec inside a familiar
 * container that the phone turns out not to have.
 *
 * Null for anything the server cannot sign a link to, which is any server
 * that is not a Stratus and anything outside its tree.
 */
fun videoSourceFor(entry: DavResource, links: ShareLinks, nowEpochSeconds: Long): VideoSource? {
    if (entry.isDirectory || !isVideo(entry)) return null
    val direct = links.link(entry.source ?: entry.path, isDirectory = false, life = ShareLife.ADay, nowEpochSeconds = nowEpochSeconds)
        ?: return null
    val hls = "$direct&$HLS"
    val title = entry.name.substringBeforeLast('.')
    return if (extensionOf(entry) in PLAYED_AS_THEY_ARE) {
        VideoSource(direct, title, fallback = hls)
    } else {
        VideoSource(hls, title)
    }
}

/** Playing films in the app, through whichever [VideoScreen] the platform has. */
class VideoController(
    private val screen: VideoScreen,
    private val links: ShareLinks,
    private val trust: TrustPolicy,
    private val now: () -> Long = { getTimeMillis() / 1000 },
) {
    fun canPlay(entry: DavResource): Boolean = !entry.isDirectory && isVideo(entry)

    fun play(entry: DavResource) {
        videoSourceFor(entry, links, now())?.let { screen.show(it, trust) }
    }
}

private fun extensionOf(entry: DavResource) = entry.name.substringAfterLast('.', "").lowercase()

private fun isVideo(entry: DavResource): Boolean {
    val type = entry.contentType?.substringBefore(';')?.trim()?.lowercase()
    if (type != null && type.startsWith("video/")) return true
    return extensionOf(entry) in VIDEO
}

private const val HLS = "hls=index.m3u8"
private val PLAYED_AS_THEY_ARE = setOf("mp4", "m4v", "mov")
private val VIDEO = PLAYED_AS_THEY_ARE + setOf("mkv", "webm", "avi", "wmv", "ts", "3gp", "mpg", "mpeg")
