package dev.stratus.core.cast

import kotlin.test.Test
import kotlin.test.assertEquals

class CastTrailTest {
    @Test
    fun aLinkKeepsItsSchemeAndRenditionAndLosesTheRest() {
        assertEquals(
            "loading application/x-mpegURL <https link hls=index.m3u8>",
            withoutLinks("loading application/x-mpegURL https://cloud.example/files/Holiday/beach.mkv?k=k1.abc.def&hls=index.m3u8"),
        )
    }

    @Test
    fun aLinkWithOnlyASignatureIsJustALink() {
        assertEquals(
            "receiver: idle, media <http link>",
            withoutLinks("receiver: idle, media http://192.168.1.20:8123/r/abcdef/beach.mkv?k=k1.abc.def"),
        )
    }

    @Test
    fun everyLinkInALineGoes() {
        assertEquals(
            "fallback <https link> and <https link format=mp3>",
            withoutLinks("fallback https://a/x.flac?k=1 and https://a/x.flac?k=1&format=mp3"),
        )
    }

    @Test
    fun aLineWithNoLinkIsUntouched() {
        assertEquals("Living room plays none of [video/x-matroska]", withoutLinks("Living room plays none of [video/x-matroska]"))
    }
}
