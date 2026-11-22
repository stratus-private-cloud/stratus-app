package dev.stratus.core.files.conformance

import dev.stratus.core.dav.DavClient
import dev.stratus.core.files.BrowserController
import dev.stratus.core.files.BrowserState
import dev.stratus.core.files.Confirmation
import dev.stratus.core.files.FileHandoff
import dev.stratus.core.net.Credentials
import dev.stratus.core.net.stratusHttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.io.Sink
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Browsing a real server, started by `make conformance`. */
class BrowserConformanceTest {

    private val url: String = requireNotNull(System.getenv("STRATUS_TEST_URL")) {
        "STRATUS_TEST_URL is unset. Run this with `make conformance`, not directly."
    }
    private val credentials = Credentials(
        System.getenv("STRATUS_TEST_USER") ?: "conformance",
        System.getenv("STRATUS_TEST_PASS") ?: "conformance-secret",
    )

    private val dav = DavClient(stratusHttpClient(CIO.create(), credentials), url)
    private val root = "/browser-${Random.nextLong().toULong().toString(16)}"

    private val handoff = object : FileHandoff {
        val received = mutableListOf<String>()
        override suspend fun open(name: String, contentType: String?, body: suspend (Sink) -> Unit) {
            received += name
        }
        override suspend fun save(name: String, contentType: String?, body: suspend (Sink) -> Unit) {
            received += name
        }
        override suspend fun send(name: String, contentType: String?, body: suspend (Sink) -> Unit) {
            received += name
        }
    }

    private suspend fun BrowserController.settled(): BrowserState = state.first { !it.busy }

    @Test
    fun listsWhatIsReallyThere() = runTest {
        dav.makeCollection(root)
        dav.makeCollection("$root/photos")
        dav.put("$root/notes.txt", "hello".encodeToByteArray(), "text/plain")

        val browser = BrowserController(dav, handoff, this)
        browser.enter(dav.stat(root))
        val listed = browser.settled()

        // Folders first, then by name: the order somebody expects, proved against
        // a server that returns them in whatever order it likes.
        assertEquals(listOf("photos", "notes.txt"), listed.entries.map { it.name })
        assertEquals(5L, listed.entries.last().size)
    }

    @Test
    fun renamesAFileAndSeesTheResult() = runTest {
        dav.makeCollection(root)
        dav.put("$root/before.txt", "x".encodeToByteArray())

        val browser = BrowserController(dav, handoff, this)
        browser.enter(dav.stat(root))
        val before = browser.settled()

        browser.ask(Confirmation.Rename(before.entries.single()))
        browser.confirmRename("after.txt")
        assertEquals(listOf("after.txt"), browser.settled().entries.map { it.name })
    }

    @Test
    fun renamesAFolderWithThingsInIt() = runTest {
        // Pinned as a refusal until the server learned to rewrite every path
        // under a directory. The app needed no change for it: it only ever
        // reported what came back.
        dav.makeCollection(root)
        dav.makeCollection("$root/full")
        dav.put("$root/full/inside.txt", "x".encodeToByteArray())

        val browser = BrowserController(dav, handoff, this)
        browser.enter(dav.stat(root))
        val listed = browser.settled()

        browser.ask(Confirmation.Rename(listed.entries.single { it.isDirectory }))
        browser.confirmRename("renamed")

        val after = browser.settled()
        assertNull(after.failure, "was ${after.failure}")
        assertEquals(listOf("renamed"), after.entries.map { it.name })
    }

    @Test
    fun aDeletionIsRestoredFromTheTrashAndThenBesideWhatTookItsName() = runTest {
        // stratus-app#179 over stratus-backend#602: the trash is at the origin,
        // beside files/, so this one browses from there as the app does.
        val origin = DavClient(stratusHttpClient(CIO.create(), credentials), url.removeSuffix("/").removeSuffix("files"))
        val folder = "/files$root"
        origin.makeCollection(folder)
        origin.put("$folder/notes.txt", "first".encodeToByteArray(), "text/plain")
        origin.delete("$folder/notes.txt")

        val bin = BrowserController(origin, handoff, this, floor = "/trash/", trash = true)
        bin.start()
        val deletion = bin.settled().entries.first { it.deletedFrom == "$folder/notes.txt" }

        bin.restore(deletion)
        val restored = bin.settled()
        assertNull(restored.failure, "was ${restored.failure}")
        assertEquals("$folder/notes.txt", restored.restored)
        assertEquals(5L, origin.stat("$folder/notes.txt").size)

        // Deleted again and the name taken meanwhile: it lands beside, as the web's Restore does.
        origin.delete("$folder/notes.txt")
        origin.put("$folder/notes.txt", "second".encodeToByteArray(), "text/plain")
        bin.refresh()
        bin.restore(bin.settled().entries.first { it.deletedFrom == "$folder/notes.txt" })
        assertEquals("$folder/notes (2).txt", bin.settled().restored)
        assertEquals(6L, origin.stat("$folder/notes.txt").size)
    }

    @Test
    fun aDeletionIsDestroyedFromTheTrash() = runTest {
        val origin = DavClient(stratusHttpClient(CIO.create(), credentials), url.removeSuffix("/").removeSuffix("files"))
        val folder = "/files$root"
        origin.makeCollection(folder)
        origin.delete(folder)

        val bin = BrowserController(origin, handoff, this, floor = "/trash/", trash = true)
        bin.start()
        val deletion = bin.settled().entries.first { it.deletedFrom == folder }
        bin.ask(Confirmation.Delete(deletion))
        bin.confirmDelete()

        val after = bin.settled()
        assertNull(after.failure, "was ${after.failure}")
        assertTrue(after.entries.none { it.deletedFrom == folder })
    }
}
