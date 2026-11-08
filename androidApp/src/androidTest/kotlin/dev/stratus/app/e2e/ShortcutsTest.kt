package dev.stratus.app.e2e

import android.content.Intent
import android.content.pm.ShortcutManager
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dev.stratus.app.MainActivity
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The launcher icon's long-press menu: a Stratus's parts, as the web app's
 * manifest offers its libraries.
 */
class ShortcutsTest : E2E() {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    // A set: the system hands them back in an order of its own.
    private fun published() = context.getSystemService(ShortcutManager::class.java).dynamicShortcuts.map { it.id }.toSet()

    @Test
    fun aStratusPutsItsPartsOnTheIcon() {
        launch()
        signIn()
        onTheBrowser()
        ui.waitUntil(15_000) { published() == setOf("files", "photos", "music", "videos") }

        openServer()
        tap("Sign out")
        see("Sign out of http")
        // The confirm button, which the dialog puts after the one on the page.
        val confirm = hasText("Sign out", substring = false) and hasClickAction()
        val buttons = ui.onAllNodes(confirm).fetchSemanticsNodes().size
        ui.onAllNodes(confirm)[buttons - 1].performClick()
        see("Server address")
        ui.waitUntil(15_000) { published().isEmpty() }
        assertEquals(emptySet(), published())
    }

    @Test
    fun aShortcutOpensItsPartOnceTheServerHasSaidWhatItHas() {
        Stratus.folder("/$unique/")
        launch(Intent(context, MainActivity::class.java).putExtra("dev.stratus.app.PART", "music"))
        signIn()
        see("/music/")
        gone(unique)
    }
}
