package dev.stratus.core.play

import dev.stratus.core.dav.DavClient
import dev.stratus.core.dav.DavError
import dev.stratus.core.dav.DavPath
import dev.stratus.core.dav.DavResource
import dev.stratus.core.media.Thumbnail
import dev.stratus.core.share.ShareLinks
import io.ktor.utils.io.toByteArray

/** One row of the library as a car or a watch browses it. */
data class LibraryItem(
    /** A path as WebDAV gives it, or [MusicLibrary.ROOT]; a playlist's track is [MusicLibrary.inPlaylist]. */
    val id: String,
    val title: String,
    val browsable: Boolean,
    val playable: Boolean,
    val artist: String? = null,
    val album: String? = null,
    /** The album whose cover this row is drawn with, where it has one. */
    val coverOf: String? = null,
)

/**
 * The music as a tree for Android Auto, a watch and the Assistant
 * (stratus-app#267): a node is a WebDAV path, a folder opens and an audio file
 * plays. On a Stratus the root is its artists and its playlists, and on any
 * other server it is the root of the server, so a car works against both.
 *
 * Choosing a track plays from it to the end of what it was chosen in, the way
 * a tap in the app does: its album, or the playlist it was listed under.
 */
class MusicLibrary(
    private val dav: DavClient,
    private val links: ShareLinks,
    private val cover: suspend (album: String) -> Thumbnail,
    private val stratus: Boolean,
    private val now: () -> Long,
) {
    suspend fun children(id: String): List<LibraryItem> = when {
        id == ROOT && stratus -> listOf(
            LibraryItem(MUSIC, "Artists", browsable = true, playable = false),
            LibraryItem(PLAYLISTS, "Playlists", browsable = true, playable = false),
        )
        id == ROOT -> folder("/")
        isPlaylist(id) -> playlist(id).filter { it.track() != null }.mapIndexed { i, entry -> entry.item(inPlaylist(id, i)) }
        else -> folder(id)
    }

    /** A queue for [id] and where in it to start, or null for something that does not play. */
    suspend fun queue(id: String): Pair<List<Track>, Int>? {
        parsePlaylistTrack(id)?.let { (path, index) ->
            val tracks = playlistTracks(path)
            return if (index in tracks.indices) tracks to index else null
        }
        if (isPlaylist(id)) return playlistTracks(id).takeIf { it.isNotEmpty() }?.let { it to 0 }

        // A track plays its folder from itself on; a folder plays from its first.
        val chosen = id.trimEnd('/')
        val folder = if (isTrack(DavResource(chosen, isDirectory = false))) chosen.substringBeforeLast('/') else chosen
        val tracks = dav.list("$folder/").filter(::isTrack).sortedBy { it.name.lowercase() }
        val artwork = if (isAlbum(folder) && tracks.isNotEmpty()) (cover(folder) as? Thumbnail.Picture)?.bytes else null
        val made = tracks.mapNotNull { entry -> trackOf(links, entry, artwork, now())?.let { entry.path to it } }
        if (made.isEmpty()) return null
        return made.map { it.second } to made.indexOfFirst { it.first == chosen }.coerceAtLeast(0)
    }

    /**
     * What "play [query]" plays: the tracks the server's search finds by that
     * word, or what is in the first folder it finds -- an album or an artist
     * by its name. Nothing where the server has no search.
     */
    suspend fun search(query: String): List<Track> {
        val found = try {
            dav.search(query).found
        } catch (_: DavError) {
            return emptyList()
        }
        val tracks = found.filter(::isTrack)
        if (tracks.isNotEmpty()) return tracks.mapNotNull { trackOf(links, it, null, now()) }
        val folder = found.firstOrNull { it.isDirectory } ?: return emptyList()
        return queue(folder.path)?.first.orEmpty()
    }

    private suspend fun folder(path: String): List<LibraryItem> {
        val listing = dav.list(path)
        val folders = listing.filter { it.isDirectory }.sortedBy { it.name.lowercase() }.map { entry ->
            LibraryItem(
                entry.path, entry.name, browsable = true, playable = isAlbum(entry.path),
                coverOf = entry.path.takeIf { isAlbum(it) },
            )
        }
        val playlists = listing.filter { !it.isDirectory && isPlaylist(it.path) }.sortedBy { it.name.lowercase() }.map {
            LibraryItem(it.path, it.name.substringBeforeLast('.'), browsable = true, playable = true)
        }
        val tracks = listing.filter(::isTrack).sortedBy { it.name.lowercase() }.map { entry ->
            val segments = entry.path.trim('/').split('/')
            val underMusic = segments.size == 4 && segments[0] == "music"
            LibraryItem(
                entry.path, entry.name.substringBeforeLast('.'), browsable = false, playable = true,
                artist = if (underMusic) segments[1] else null,
                album = if (underMusic) segments[2] else null,
                coverOf = if (underMusic) entry.path.substringBeforeLast('/') else null,
            )
        }
        return folders + playlists + tracks
    }

    private suspend fun playlist(path: String): List<PlaylistEntry> =
        parsePlaylist(dav.read(path) { it.toByteArray().decodeToString() })

    private suspend fun playlistTracks(path: String): List<Track> = playlist(path).mapNotNull { it.track() }

    private fun PlaylistEntry.item(id: String) =
        LibraryItem(id, title, browsable = false, playable = true, artist = artist)

    private fun PlaylistEntry.track(): Track? =
        trackOf(links, DavResource(path, isDirectory = false), null, now())?.copy(title = title, artist = artist)

    companion object {
        const val ROOT = "root"
        private const val MUSIC = "/music/"
        private const val PLAYLISTS = "/playlists/"

        /** The id of a playlist's [index]th track, which plays the playlist from there. */
        fun inPlaylist(playlist: String, index: Int) = "$playlist#$index"

        private fun parsePlaylistTrack(id: String): Pair<String, Int>? {
            val path = id.substringBeforeLast('#', "")
            val index = id.substringAfterLast('#').toIntOrNull()
            return if (path.isNotEmpty() && index != null && isPlaylist(path)) path to index else null
        }

        private fun isPlaylist(path: String) = path.substringAfterLast('.').lowercase() in setOf("m3u", "m3u8")

        /** /music/<artist>/<album>, the one kind of folder a server has a cover for. */
        private fun isAlbum(path: String): Boolean {
            val segments = path.trim('/').split('/')
            return segments.size == 3 && segments[0] == "music"
        }
    }
}

internal data class PlaylistEntry(val path: String, val title: String, val artist: String?)

/**
 * An M3U playlist's entries: the lines that are paths from the origin, which
 * is how a Stratus writes them, with `#EXTINF`'s text naming the next one as
 * `Artist - Title` where it has both. A URL is left out, since it may be on
 * another server and nothing here could sign it.
 */
internal fun parsePlaylist(text: String): List<PlaylistEntry> {
    val entries = mutableListOf<PlaylistEntry>()
    var said: String? = null
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        when {
            line.isEmpty() -> Unit
            line.startsWith("#EXTINF:") -> said = line.substringAfter(',', "").trim().ifEmpty { null }
            line.startsWith("#") -> Unit
            else -> {
                if (line.startsWith("/")) {
                    val path = DavPath.decode(line)
                    val name = path.substringAfterLast('/').substringBeforeLast('.')
                    val artist = said?.substringBefore(" - ", "")?.ifEmpty { null }
                    val title = said?.let { if (artist != null) it.substringAfter(" - ") else it } ?: name
                    entries += PlaylistEntry(path, title, artist)
                }
                said = null
            }
        }
    }
    return entries
}
