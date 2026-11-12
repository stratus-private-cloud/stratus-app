package dev.stratus.app.e2e

import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import android.app.Activity
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.stratus.core.play.VideoActivity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserTest : E2E() {

    @Test
    fun walksIntoAFolderAndBackOutWithTheSystemGesture() {
        Stratus.folder("/$unique/")
        Stratus.file("/$unique/inside.txt")
        signedIn()

        tap(unique)
        see("inside.txt")
        Phone.device.pressBack()
        see(unique)
        gone("inside.txt")
    }

    // Back at the root leaves the app without destroying the activity, and
    // the gesture used to stay handed to the system once it had: coming back,
    // back from inside a folder left the app again instead of walking up.
    @Test
    fun theGestureStillWalksUpAfterItHasLeftTheApp() {
        Stratus.folder("/$unique/")
        Stratus.file("/$unique/inside.txt")
        signedIn()
        see(unique)

        Phone.device.pressBack()
        Phone.toHomeAndBack()

        tap(unique)
        see("inside.txt")
        Phone.device.pressBack()
        see(unique)
        gone("inside.txt")
    }

    // stratus-app#82: something another client added shows without walking
    // out of the folder and back.
    @Test
    fun pullingTheListDownShowsWhatWasAddedSince() {
        Stratus.folder("/$unique/")
        Stratus.file("/$unique/first.txt")
        signedIn()
        tap(unique)
        see("first.txt")

        Stratus.file("/$unique/second.txt")
        ui.onAllNodes(hasScrollAction())[0].performTouchInput { swipeDown() }

        see("second.txt")
    }

    @Test
    fun aFileSaysWhatItIsFromItsMenu() {
        Stratus.folder("/$unique/")
        Stratus.file("/$unique/notes.txt")
        signedIn()
        tap(unique)

        tap("notes.txt")
        tap("Details")

        see("$unique/notes.txt")
        tap("Close")
    }

    @Test
    fun renamesAFileOnTheServer() {
        Stratus.folder("/$unique/")
        Stratus.file("/$unique/before.txt")
        signedIn()
        tap(unique)

        tap("before.txt")
        tap("Rename")
        ui.onNode(hasSetTextAction()).performTextClearance()
        ui.onNode(hasSetTextAction()).performTextInput("after.txt")
        tap("Rename")

        see("after.txt")
        assertEquals(listOf("after.txt"), Stratus.names("/$unique/"))
    }

    @Test
    fun deletingAsksFirstAndThenRemovesItFromTheServer() {
        Stratus.folder("/$unique/")
        Stratus.file("/$unique/doomed.txt")
        signedIn()
        tap(unique)

        tap("doomed.txt")
        tap("Delete")
        see("cannot be undone")
        tap("Cancel")
        assertTrue(Stratus.exists("/$unique/doomed.txt"), "cancelling deleted it")

        tap("doomed.txt")
        tap("Delete")
        tap("Delete")
        gone("doomed.txt")
        assertFalse(Stratus.exists("/$unique/doomed.txt"))
    }

    // stratus-app#180: through the media session in its own service. The
    // bytes are not audio, so this proves the queue reaches the player and
    // comes back to the screen, not that anything is heard.
    @Test
    fun aTrackIsHandedToThePlayer() {
        Stratus.folder("/$unique/")
        Stratus.file("/$unique/tune-$unique.mp3")
        signedIn()
        tap(unique)

        tap("tune-$unique.mp3")
        tap("Play")
        see("tune-$unique", substring = false)
    }

    // A film opens full screen in an activity of its own. The bytes are not a
    // film, so this proves the hand-over, not the picture.
    @Test
    fun aFilmOpensInThePlayer() {
        Stratus.folder("/$unique/")
        Stratus.file("/$unique/clip-$unique.mp4")
        signedIn()
        tap(unique)

        tap("clip-$unique.mp4")
        tap("Play")
        // Asked of the instrumentation rather than of the screen: a player
        // view does not show up in UiAutomator's tree by its description.
        ui.waitUntil(15_000) { inFront() is VideoActivity }
        goBack()
        see("clip-$unique.mp4")
    }

    @Test
    fun whatWasDeletedIsInTheTrash() {
        Stratus.folder("/$unique/")
        Stratus.file("/$unique/binned-$unique.txt")
        signedIn()
        tap(unique)

        tap("binned-$unique.txt")
        tap("Delete")
        tap("Delete")
        gone("binned-$unique.txt")

        openSettings()
        tap("Trash")
        see("binned-$unique.txt")
    }

    @Test
    fun aFolderCanBeRenamedFromItsMenu() {
        Stratus.folder("/$unique/")
        signedIn()

        ui.onNodeWithContentDescription("Actions on $unique").performClick()
        tap("Rename")
        ui.onNode(hasSetTextAction()).performTextClearance()
        ui.onNode(hasSetTextAction()).performTextInput("${unique}b")
        tap("Rename")

        see("${unique}b")
        assertTrue(Stratus.exists("/${unique}b/"))
    }

    @Test
    fun severalAreSelectedAndDeletedTogether() {
        Stratus.folder("/$unique/")
        Stratus.file("/$unique/one.txt")
        Stratus.file("/$unique/two.txt")
        Stratus.file("/$unique/kept.txt")
        signedIn()
        tap(unique)

        see("one.txt").performTouchInput { longClick() }
        tap("two.txt")
        see("2 selected")
        ui.onNodeWithContentDescription("Delete the selected").performClick()
        see("Delete 2 items?")
        tap("Delete")

        gone("one.txt")
        gone("two.txt")
        assertEquals(listOf("kept.txt"), Stratus.names("/$unique/"))
    }

    @Test
    fun downloadingKeepsACopyInDownloads() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        Stratus.folder("/$unique/")
        Stratus.file("/$unique/$unique.bin", bytes)
        signedIn()
        tap(unique)

        tap("$unique.bin")
        tap("Download")

        ui.waitUntil(20_000) { Phone.downloads("$unique.bin").isNotEmpty() }
        assertEquals(listOf(bytes.size.toLong()), Phone.downloads("$unique.bin"))
    }

    @Test
    fun openingAFileHandsItToTheSystemWithoutCrashing() {
        Stratus.folder("/$unique/")
        Stratus.file("/$unique/note.txt")
        signedIn()
        tap(unique)

        tap("note.txt")
        tap("Open")
        // An emulator with no viewer still has the chooser, which says so.
        ui.waitUntil(10_000) { Phone.inFront() != Phone.context.packageName }
        Phone.backToApp()
        see("note.txt")
    }

    // No Play Services on this image, so this is the phone that has none: the
    // Chromecasts are missing and the DLNA renderers are not (stratus-app#153),
    // so the button stays and looking for a screen must not break anything.
    // There is no renderer on the emulator's network, which is the point: an
    // empty search is the commonest thing it will ever find.
    @Test
    fun withoutPlayServicesTheRenderersAreStillLookedFor() {
        Stratus.folder("/$unique/")
        Stratus.file("/$unique/film.mp4")
        signedIn()
        tap(unique)

        tap("film.mp4")
        tap("Cast to a screen")
        see("Looking for screens on this network…")
        tap("Cancel")
        see("film.mp4")
    }

    private fun inFront(): Activity? {
        var found: Activity? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
        }
        return found
    }
}
