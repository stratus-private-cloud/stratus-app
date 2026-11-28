package dev.stratus.app.e2e

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ShortcutManager
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.stratus.app.ShareActivity
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

class UploadTest : E2E() {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /** A file the app's own provider serves, which is the shape the picker answers in: a content URI. */
    private fun pickable(name: String, text: String) =
        File(context.cacheDir, "handoff").apply { mkdirs() }.resolve(name).let { file ->
            file.writeText(text)
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }

    /**
     * stratus-app#127: from the folder on screen, with backup off, a file
     * lands beside one of its name rather than over it.
     *
     * The picker is the system's, so it is answered from inside the process
     * by a monitor -- the API 26 one that sees every intent -- with the
     * content URI it would have given. Everything after that is the app's
     * own: the grant it tries for, the queue, the pass the worker runs and
     * the folder refreshing once the file is there.
     */
    @Test
    fun aPickedFileLandsInTheFolderBesideOneOfItsName() {
        Stratus.folder("/$unique/")
        Stratus.file("/$unique/scan.txt", "already there".encodeToByteArray())
        val picked = pickable("scan.txt", "picked by hand")
        signedIn()
        tap(unique)
        see("scan.txt")

        val picker = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.action == Intent.ACTION_OPEN_DOCUMENT) {
                    Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(picked))
                } else {
                    null
                }
        }
        instrumentation.addMonitor(picker)
        try {
            ui.onNodeWithContentDescription("Upload").performClick()
            waitFor(60_000) { Stratus.exists("/$unique/scan (2).txt") }
        } finally {
            instrumentation.removeMonitor(picker)
        }

        assertEquals("picked by hand", Stratus.read("/$unique/scan (2).txt"))
        assertEquals("already there", Stratus.read("/$unique/scan.txt"))
        // The row gave way to the file, in the folder somebody is looking at.
        see("scan (2).txt")
        gone("Uploading", timeoutMs = 30_000)
    }

    /**
     * stratus-app#269: a folder something was picked into is offered to the
     * share sheet, and a share sent through that shortcut lands in it --
     * the sheet itself being the system's, the intent it would send is sent.
     */
    @Test
    fun aFolderPickedIntoIsOfferedToTheShareSheetAndAShareLandsThere() {
        Stratus.folder("/$unique/")
        val picked = pickable("scan.txt", "picked by hand")
        signedIn()
        tap(unique)

        val picker = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
                if (intent.action == Intent.ACTION_OPEN_DOCUMENT) {
                    Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(picked))
                } else {
                    null
                }
        }
        instrumentation.addMonitor(picker)
        try {
            ui.onNodeWithContentDescription("Upload").performClick()
            waitFor(60_000) { Stratus.exists("/$unique/scan.txt") }
        } finally {
            instrumentation.removeMonitor(picker)
        }

        val id = "folder:files/$unique"
        val shortcuts = context.getSystemService(ShortcutManager::class.java)
        waitFor(15_000, { "shortcuts: ${shortcuts.dynamicShortcuts.map { it.id }}" }) {
            shortcuts.dynamicShortcuts.any { it.id == id }
        }

        val shared = pickable("note.txt", "shared into it")
        context.startActivity(
            Intent(context, ShareActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, shared)
                .putExtra(Intent.EXTRA_SHORTCUT_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        waitFor(60_000) { Stratus.exists("/$unique/note.txt") }
        assertEquals("shared into it", Stratus.read("/$unique/note.txt"))
    }

    private fun waitFor(timeoutMs: Long, said: () -> String = { "The screen showed:\n${screen()}" }, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(500)
        }
        throw AssertionError("it never arrived. ${said()}")
    }
}
