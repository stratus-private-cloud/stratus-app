package dev.stratus.core.session

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.stratus.core.backup.Asset
import dev.stratus.core.backup.AssetPart
import dev.stratus.core.backup.AssetSource
import dev.stratus.core.backup.Backup
import dev.stratus.core.backup.BackupDatabase
import dev.stratus.core.backup.Connections
import dev.stratus.core.backup.MediaAccess
import dev.stratus.core.backup.MediaSource
import dev.stratus.core.server.Server
import dev.stratus.core.server.ServerStore
import dev.stratus.core.net.Credentials
import dev.stratus.core.store.ReportingConsent
import dev.stratus.core.store.SecureStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.RawSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class SessionMemoryStore : SecureStore {
    private val kept = mutableMapOf<String, String>()
    private var held: CompletableDeferred<Unit>? = null

    /** The next read answers with what is there now, but only once [gate] opens. */
    fun holdNextRead(gate: CompletableDeferred<Unit>) { held = gate }

    override suspend fun read(key: String): String? {
        val answer = kept[key]
        held?.let { held = null; it.await() }
        return answer
    }
    override suspend fun write(key: String, value: String) { kept[key] = value }
    override suspend fun delete(key: String) { kept.remove(key) }
}

/** A camera roll whose permission can be granted from outside, as Settings does. */
private class Roll : AssetSource {
    var access = MediaAccess.None
    var listings = 0
    override suspend fun access() = access
    override suspend fun sources(): List<MediaSource> {
        listings++
        return if (access == MediaAccess.None) emptyList() else listOf(MediaSource("camera", "Camera", 3))
    }
    override suspend fun assets(from: Set<String>, addedAfterEpochMs: Long) = emptyList<Asset>()
    override suspend fun open(localId: String, part: AssetPart, from: Long): RawSource = error("not read")
}

class SignedInControllerTest {

    private val store = SessionMemoryStore()
    private val instances = ServerStore(store)
    private val roll = Roll()
    private val backup = Backup(
        instances,
        BackupDatabase(BundledSQLiteDriver().open(":memory:")),
        roll,
        Connections { null },
    )
    private val asked = mutableListOf<Set<Ask>>()
    private val reported = mutableListOf<Boolean>()
    private var signedOut = false

    private val controller = SignedInController(
        instances = instances,
        backup = backup,
        consent = ReportingConsent(store),
        signOut = { signedOut = true; instances.clear() },
        ask = { asked += it },
        notificationsAsked = AskedOnce(store, "notifications-asked"),
        reportingChanged = { reported += it },
    )

    private suspend fun signedIn(host: String = "home") =
        instances.put(Server("https://$host.example/dav/", "edu"), Credentials("edu", "secret"))

    // stratus-app#75: granted in Settings, and the Folders screen still said no.
    @Test
    fun aPermissionGrantedWhileAwayIsSeenOnResuming() = runTest {
        controller.start()
        assertEquals(MediaAccess.None, controller.state.value.access)
        assertTrue(controller.state.value.folders.isEmpty())

        roll.access = MediaAccess.Full
        controller.resumed()

        assertEquals(MediaAccess.Full, controller.state.value.access)
        assertEquals(listOf("camera"), controller.state.value.folders.map { it.id })
    }

    @Test
    fun openingFoldersReadsTheAccessAgain() = runTest {
        controller.start()
        roll.access = MediaAccess.Partial
        controller.openingFolders()
        assertEquals(MediaAccess.Partial, controller.state.value.access)
    }

    // A MediaStore query per resume would be the price of the fix with nothing
    // bought by it.
    @Test
    fun theFoldersAreListedAgainOnlyWhenTheAccessMoved() = runTest {
        roll.access = MediaAccess.Full
        controller.start()
        val before = roll.listings

        controller.resumed()
        controller.resumed()

        assertEquals(before, roll.listings)
    }

    @Test
    fun choosingFoldersWithoutAccessAsksForIt() = runTest {
        signedIn()
        controller.start()

        // Choosing none is choosing no backup (stratus-app#131), so there is
        // nothing to ask permission for.
        controller.chooseSources(emptySet())
        assertEquals(emptyList(), asked, "choosing nothing needs nothing")

        controller.chooseSources(setOf("dcim"))
        // One request for both, because Android shows one at a time.
        assertEquals(listOf(setOf(Ask.Photos, Ask.Notifications)), asked)
        assertTrue(controller.state.value.server!!.backupEnabled)
    }

    @Test
    fun choosingFoldersWithAccessAsksNothingMore() = runTest {
        signedIn()
        roll.access = MediaAccess.Full
        controller.start()

        controller.chooseSources(setOf("dcim"))
        assertEquals(listOf(setOf(Ask.Notifications)), asked, "the library was already readable")
    }

    // stratus-app#81: a refusal is an answer, so it is asked once and not on
    // every change.
    @Test
    fun theNotificationPermissionIsAskedForOnceAndThenLeftAlone() = runTest {
        signedIn()
        roll.access = MediaAccess.Full
        controller.start()

        controller.chooseSources(setOf("dcim"))
        controller.chooseSources(emptySet())
        controller.chooseSources(setOf("dcim"))

        assertEquals(listOf(setOf(Ask.Notifications)), asked)
    }

    @Test
    fun theFoldersButtonAsksForThePhotosAlone() = runTest {
        controller.start()
        controller.askForPhotos()
        assertEquals(listOf(setOf(Ask.Photos)), asked)
    }

    @Test
    fun reportingIsRememberedAndHandedToTheReporter() = runTest {
        controller.start()
        assertFalse(controller.state.value.reporting)

        controller.setReporting(true)

        assertEquals(listOf(true), reported)
        assertTrue(ReportingConsent(store).granted())
        assertTrue(controller.state.value.reporting)
    }

    @Test
    fun signingOutForgetsTheServerAndShowsItGone() = runTest {
        signedIn()
        controller.start()

        controller.signOut()

        assertTrue(signedOut)
        assertNull(controller.state.value.server)
    }

    // stratus-app#220: the once-a-second poll read the server just before the
    // wifi switch was turned off, and published what it read just after --
    // so the switch went back on until the next poll.
    @Test
    fun aPollThatReadBeforeTheSwitchDoesNotPutTheOldValueBack() = runTest {
        signedIn()
        controller.start()
        assertTrue(controller.state.value.server!!.onlyOnWifi)

        val gate = CompletableDeferred<Unit>()
        store.holdNextRead(gate)
        val poll = launch { controller.refresh() }
        runCurrent()
        val switch = launch { controller.setOnlyOnWifi(false) }
        runCurrent()
        gate.complete(Unit)
        poll.join()
        switch.join()

        assertFalse(controller.state.value.server!!.onlyOnWifi)
    }
}
