package dev.stratus.app.e2e

import android.content.ComponentName
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import dev.stratus.core.play.PlaybackService
import org.junit.Test
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The music as Android Auto sees it (stratus-app#267): a media browser
 * connected to the player's service, walking the tree a car draws and choosing
 * a track by its id alone, which is all a car sends.
 */
class MusicLibraryTest : E2E() {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** A browser's calls belong on the thread it was built for, which is the main one. */
    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun <T> Future<T>.answer(): T = get(30, TimeUnit.SECONDS)

    private fun MediaBrowser.titles(id: String): Map<String, MediaItem> =
        onMain { getChildren(id, 0, 100, null) }.answer().value.orEmpty().associateBy { it.mediaMetadata.title.toString() }

    @Test
    fun aCarBrowsesToATrackAndPlaysItByItsIdAlone() {
        launch()
        signIn()
        onTheBrowser()
        // A folder of its own: one called Music in the shared tree would be what
        // the sign-in test finds when it taps the Music section.
        Stratus.folder("/$unique/")
        Stratus.file("/$unique/track.opus", instrumentation.context.assets.open("tagged.opus").readBytes())

        val token = SessionToken(Phone.context, ComponentName(Phone.context, PlaybackService::class.java))
        val browser = onMain {
            MediaBrowser.Builder(Phone.context, token).setApplicationLooper(Looper.getMainLooper()).buildAsync()
        }.answer()
        try {
            val root = onMain { browser.getLibraryRoot(null) }.answer().value!!
            assertTrue("Artists" in browser.titles(root.mediaId), "the root was not a Stratus's")

            // The server reads the tags after the upload, so the artist arrives a moment later.
            val deadline = SystemClock.elapsedRealtime() + 60_000
            var artists = browser.titles("/music/")
            while ("Yellow Ostrich" !in artists && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(1_000)
                artists = browser.titles("/music/")
            }
            val artist = artists["Yellow Ostrich"] ?: fail("the artist never appeared: ${artists.keys}")
            val album = browser.titles(artist.mediaId)["Strange Land"] ?: fail("no album under the artist")
            assertTrue(album.mediaMetadata.isPlayable == true, "an album should play")
            val tracks = browser.titles(album.mediaId)
            val track = tracks.values.firstOrNull { "Stay At Home" in it.mediaMetadata.title.toString() }
                ?: fail("no track in ${album.mediaId}: ${tracks.keys}")

            onMain {
                browser.setMediaItem(MediaItem.Builder().setMediaId(track.mediaId).build())
                browser.prepare()
                browser.play()
            }
            val playing = SystemClock.elapsedRealtime() + 30_000
            while (!onMain { browser.isPlaying } && SystemClock.elapsedRealtime() < playing) SystemClock.sleep(500)
            assertTrue(onMain { browser.isPlaying }, "the chosen track never played: ${onMain { browser.playerError }}")
        } finally {
            onMain {
                browser.stop()
                browser.release()
            }
            Stratus.remove("/$unique/")
        }
    }
}
