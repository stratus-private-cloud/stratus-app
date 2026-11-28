package dev.stratus.core.play

import dev.stratus.core.dav.DavResource
import dev.stratus.core.media.Thumbnail
import dev.stratus.core.net.TrustPolicy
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
    fun canPlay(entry: DavResource): Boolean = isTrack(entry)

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

    private fun trackOf(entry: DavResource, artwork: ByteArray?): Track? = trackOf(links, entry, artwork, now())
}
