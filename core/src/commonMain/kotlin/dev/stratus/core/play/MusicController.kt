package dev.stratus.core.play

import dev.stratus.core.dav.DavResource
import dev.stratus.core.media.Thumbnail
import dev.stratus.core.net.TrustPolicy
import dev.stratus.core.share.ShareLife
import dev.stratus.core.share.ShareLinks
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Playing what a folder holds, from the track somebody chose to the end of it.
 *
 * The URLs are signed links, the same ones a television is given: the player
 * runs outside the app's HTTP client, and a link is the one credential it can
 * carry. A day's life, because an album is listened to in an evening and a
 * link cannot be taken back.
 */
class MusicController(
    private val player: Player,
    private val links: ShareLinks,
    /** The album's cover, for the lock screen; nothing where there is none. */
    private val cover: suspend (album: String) -> Thumbnail,
    private val trust: TrustPolicy,
    private val scope: CoroutineScope,
    private val now: () -> Long = { getTimeMillis() / 1000 },
) {
    val state: StateFlow<PlayerState> get() = player.state

    fun position(): Pair<Long, Long?> = player.position()

    /** Whether there is anything in this file for the player. */
    fun canPlay(entry: DavResource): Boolean = !entry.isDirectory && isAudio(entry)

    /**
     * Plays [chosen] and then whatever follows it among [siblings], in the
     * order they are listed -- which for an album is its track numbers,
     * because the server names tracks by them.
     */
    fun play(siblings: List<DavResource>, chosen: DavResource) {
        val tracks = siblings.filter(::canPlay)
        if (tracks.none { it.path == chosen.path }) return
        scope.launch {
            val album = chosen.path.trimEnd('/').substringBeforeLast('/')
            val artwork = (cover(album) as? Thumbnail.Picture)?.bytes
            val made = tracks.mapNotNull { entry -> trackOf(entry, artwork)?.let { entry.path to it } }
            val first = made.indexOfFirst { it.first == chosen.path }
            if (first >= 0) player.play(made.map { it.second }, first, trust)
        }
    }

    fun toggle() = player.toggle()
    fun next() = player.next()
    fun previous() = player.previous()
    fun seekTo(ms: Long) = player.seekTo(ms)
    fun stop() = player.stop()

    private fun trackOf(entry: DavResource, artwork: ByteArray?): Track? {
        // A file under /music/ is signed at its address in the tree, which is
        // the only one the server answers a link at.
        val url = links.link(entry.source ?: entry.path, isDirectory = false, life = ShareLife.ADay, nowEpochSeconds = now())
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

    private fun isAudio(entry: DavResource): Boolean {
        val type = entry.contentType?.substringBefore(';')?.trim()?.lowercase()
        if (type != null && type.startsWith("audio/")) return true
        return entry.name.substringAfterLast('.', "").lowercase() in AUDIO
    }

    private companion object {
        val AUDIO = setOf("mp3", "m4a", "flac", "ogg", "opus", "wav", "aac", "alac", "aiff")
    }
}
