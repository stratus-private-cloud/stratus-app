package dev.stratus.core.play

import dev.stratus.core.dav.DavResource
import dev.stratus.core.media.Thumbnail
import dev.stratus.core.net.Credentials
import dev.stratus.core.net.TrustPolicy
import dev.stratus.core.share.ShareLinks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakePlayer : Player {
    val played = mutableListOf<Pair<List<Track>, Int>>()
    override val state: StateFlow<PlayerState> = MutableStateFlow(PlayerState())
    override fun position(): Pair<Long, Long?> = 0L to null
    override fun play(queue: List<Track>, start: Int, trust: TrustPolicy) { played += queue to start }
    override fun toggle() {}
    override fun next() {}
    override fun previous() {}
    override fun seekTo(ms: Long) {}
    override fun stop() {}
}

class MusicControllerTest {

    private val player = FakePlayer()
    private val links = ShareLinks("https://host/", Credentials("edu", "secret"))
    private val asked = mutableListOf<String>()

    private fun track(name: String, type: String? = "audio/flac") = DavResource(
        path = "/music/Autechre/Amber/$name",
        isDirectory = false,
        contentType = type,
        source = "/files/Music/Autechre/Amber/$name",
    )

    @Test
    fun anAlbumPlaysFromTheChosenTrackWithItsCover() = runTest {
        val music = MusicController(player, links, { asked += it; Thumbnail.Picture(byteArrayOf(1, 2)) }, TrustPolicy(), this) { 1_700_000_000 }
        val album = listOf(track("01 Foil.flac"), track("cover.jpg", "image/jpeg"), track("02 Montreal.flac"), track("03 Silverside.flac"))

        music.play(album, album[2])
        testScheduler.advanceUntilIdle()

        val (queue, start) = player.played.single()
        assertEquals(listOf("01 Foil", "02 Montreal", "03 Silverside"), queue.map { it.title })
        assertEquals(1, start)
        assertEquals(listOf("/music/Autechre/Amber"), asked)
        assertContentEquals(byteArrayOf(1, 2), queue[1].artwork)
        assertEquals("Amber", queue[1].album)
        assertEquals("Autechre", queue[1].artist)
        // Signed where the file really is: the only address a link works at.
        assertTrue(queue[1].url.startsWith("https://host/files/Music/Autechre/Amber/02%20Montreal.flac?k="), queue[1].url)
    }

    @Test
    fun aTrackWithNoCoverStillPlays() = runTest {
        val music = MusicController(player, links, { Thumbnail.None }, TrustPolicy(), this) { 1_700_000_000 }
        val album = listOf(track("01 Foil.flac"))

        music.play(album, album[0])
        testScheduler.advanceUntilIdle()

        assertEquals(null, player.played.single().first.single().artwork)
    }

    @Test
    fun anythingButAudioIsLeftToTheMenu() = runTest {
        val music = MusicController(player, links, { Thumbnail.None }, TrustPolicy(), this) { 1_700_000_000 }
        val picture = track("cover.jpg", "image/jpeg")

        assertTrue(!music.canPlay(picture))
        assertTrue(music.canPlay(track("04 Nine.mp3", type = null)))
        music.play(listOf(picture), picture)
        testScheduler.advanceUntilIdle()
        assertTrue(player.played.isEmpty())
    }
}
