package dev.stratus.core.play

import dev.stratus.core.dav.DavResource
import dev.stratus.core.net.Credentials
import dev.stratus.core.net.TrustPolicy
import dev.stratus.core.share.ShareLinks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoTest {

    private val links = ShareLinks("https://host/", Credentials("edu", "secret"))

    private fun film(name: String, type: String? = null) = DavResource(
        path = "/videos/2024/06/$name",
        isDirectory = false,
        contentType = type,
        source = "/files/Camera/$name",
    )

    @Test
    fun whatAPhoneRecordsIsPlayedAsItIsWithTheHlsBehindIt() {
        val source = videoSourceFor(film("IMG_0042.MOV", "video/quicktime"), links, 1_700_000_000)!!
        assertTrue(source.url.startsWith("https://host/files/Camera/IMG_0042.MOV?k="), source.url)
        assertEquals(source.url + "&hls=index.m3u8", source.fallback)
        assertEquals("IMG_0042", source.title)
    }

    @Test
    fun anythingElseIsTheServersHls() {
        val source = videoSourceFor(film("Film.mkv", "video/x-matroska"), links, 1_700_000_000)!!
        assertTrue(source.url.endsWith("&hls=index.m3u8"), source.url)
        assertNull(source.fallback)
    }

    @Test
    fun aFilmIsKnownByItsNameWhereTheServerGaveNoType() {
        assertTrue(videoSourceFor(film("clip.mp4"), links, 1_700_000_000) != null)
        assertNull(videoSourceFor(film("song.mp3", "audio/mpeg"), links, 1_700_000_000))
    }

    @Test
    fun theControllerHandsTheScreenTheSourceAndTheTrust() {
        val shown = mutableListOf<Pair<VideoSource, TrustPolicy>>()
        val trust = TrustPolicy(pin = "AB:CD")
        val videos = VideoController({ s, t -> shown += s to t }, links, trust) { 1_700_000_000 }

        videos.play(film("clip.mp4"))

        assertEquals("clip", shown.single().first.title)
        assertEquals("AB:CD", shown.single().second.pin)
    }
}
