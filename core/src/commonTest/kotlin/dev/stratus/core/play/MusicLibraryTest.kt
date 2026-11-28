package dev.stratus.core.play

import dev.stratus.core.dav.DavClient
import dev.stratus.core.media.Thumbnail
import dev.stratus.core.net.Credentials
import dev.stratus.core.share.ShareLinks
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicLibraryTest {

    private fun listing(vararg hrefs: String) = buildString {
        append("""<multistatus xmlns="DAV:">""")
        for (href in hrefs) {
            append("<response><href>$href</href><propstat><prop><resourcetype>")
            if (href.endsWith("/")) append("<collection/>")
            append("</resourcetype>")
            // Where a file under music/ really is, as a Stratus says it.
            if (href.startsWith("/music/") && !href.endsWith("/")) {
                append("""<source xmlns="https://github.com/C0piIot/stratus"><href xmlns="DAV:">""")
                append(href.replace("/music/", "/files/Music/")).append("</href></source>")
            }
            append("</prop><status>HTTP/1.1 200 OK</status></propstat></response>")
        }
        append("</multistatus>")
    }

    private val playlist = """
        #EXTM3U
        #PLAYLIST:Mix
        #EXTINF:60,Autechre - Foil
        /files/Music/Autechre/Amber/01%20Foil.flac
        #EXTINF:60,Untitled
        /files/Music/x.mp3
        https://elsewhere.example/stream.mp3
    """.trimIndent()

    private val tree = mapOf(
        "/" to listing("/files/", "/music/"),
        "/music/" to listing("/music/Autechre/", "/music/Boards/"),
        "/music/Autechre/" to listing("/music/Autechre/Amber/"),
        "/music/Autechre/Amber/" to listing(
            "/music/Autechre/Amber/02 Montreal.flac",
            "/music/Autechre/Amber/cover.jpg",
            "/music/Autechre/Amber/01 Foil.flac",
        ),
        "/playlists/" to listing("/playlists/Mix.m3u8"),
    )

    private val covers = mutableListOf<String>()

    private fun library(stratus: Boolean = true, search: String? = null) = MusicLibrary(
        DavClient(
            HttpClient(
                MockEngine { request ->
                    val path = request.url.encodedPath.replace("%20", " ")
                    when {
                        request.method.value == "SEARCH" && search != null -> respond(search, HttpStatusCode.MultiStatus)
                        request.method.value == "SEARCH" -> respond("", HttpStatusCode.MethodNotAllowed)
                        path == "/playlists/Mix.m3u8" -> respond(playlist, HttpStatusCode.OK)
                        path in tree -> respond(tree.getValue(path), HttpStatusCode.MultiStatus)
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                },
            ),
            "https://host/",
        ),
        ShareLinks("https://host/", Credentials("edu", "secret")),
        cover = { album -> covers += album; Thumbnail.Picture(byteArrayOf(7)) },
        stratus = stratus,
        now = { 1_700_000_000 },
    )

    @Test
    fun onAStratusTheRootIsItsArtistsAndItsPlaylists() = runTest {
        assertEquals(listOf("/music/", "/playlists/"), library().children(MusicLibrary.ROOT).map { it.id })
    }

    @Test
    fun anywhereElseTheRootIsTheServersRoot() = runTest {
        assertEquals(listOf("/files", "/music"), library(stratus = false).children(MusicLibrary.ROOT).map { it.id })
    }

    @Test
    fun anAlbumIsAFolderThatPlaysAndHoldsItsTracksInOrder() = runTest {
        val album = library().children("/music/Autechre/").single()
        assertTrue(album.browsable && album.playable)
        assertEquals("/music/Autechre/Amber", album.coverOf)

        val tracks = library().children("/music/Autechre/Amber/")
        assertEquals(listOf("01 Foil", "02 Montreal"), tracks.map { it.title })
        assertEquals("Autechre", tracks.first().artist)
        assertTrue(tracks.none { it.browsable })
    }

    @Test
    fun aTrackPlaysItsAlbumFromItselfWithTheCover() = runTest {
        val (queue, start) = library().queue("/music/Autechre/Amber/02 Montreal.flac")!!
        assertEquals(listOf("01 Foil", "02 Montreal"), queue.map { it.title })
        assertEquals(1, start)
        assertContentEquals(byteArrayOf(7), queue[1].artwork)
        assertEquals(listOf("/music/Autechre/Amber"), covers)
    }

    @Test
    fun aPlaylistListsWhatCanBeSignedAndPlaysFromTheTrackChosen() = runTest {
        val items = library().children("/playlists/Mix.m3u8")
        assertEquals(listOf("Foil", "Untitled"), items.map { it.title })
        assertEquals("Autechre", items.first().artist)

        val (queue, start) = library().queue(items[1].id)!!
        assertEquals(2, queue.size)
        assertEquals(1, start)
        assertTrue(queue[0].url.startsWith("https://host/files/Music/Autechre/Amber/01%20Foil.flac?"), queue[0].url)
    }

    @Test
    fun aServerWithoutSearchFindsNothingToPlay() = runTest {
        assertEquals(emptyList(), library().search("amber"))
    }

    @Test
    fun aSearchThatFindsAFolderPlaysWhatIsInIt() = runTest {
        val found = listing("/music/Autechre/Amber/")
        assertEquals(listOf("01 Foil", "02 Montreal"), library(search = found).search("amber").map { it.title })
    }

    @Test
    fun somethingThatDoesNotPlayHasNoQueue() = runTest {
        assertNull(library().queue("/music/Autechre/"))
    }

    @Test
    fun aPlaylistReadsPathsAndNamesAndLeavesUrlsOut() {
        val entries = parsePlaylist(playlist)
        assertEquals(listOf("/files/Music/Autechre/Amber/01 Foil.flac", "/files/Music/x.mp3"), entries.map { it.path })
        assertEquals(PlaylistEntry("/files/Music/x.mp3", "Untitled", null), entries[1])
    }
}
