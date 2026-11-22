package dev.stratus.core.backup

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.stratus.core.dav.DavClient
import dev.stratus.core.dav.DavPath
import dev.stratus.core.net.Credentials
import dev.stratus.core.server.Server
import dev.stratus.core.server.ServerStore
import dev.stratus.core.store.SecureStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class HandStore : SecureStore {
    private val kept = mutableMapOf<String, String>()
    override suspend fun read(key: String) = kept[key]
    override suspend fun write(key: String, value: String) { kept[key] = value }
    override suspend fun delete(key: String) { kept.remove(key) }
}

/** A camera roll that counts how often it is looked at, which with backup off must be never. */
private class WatchedRoll : AssetSource {
    var looked = 0
    override suspend fun access() = MediaAccess.Full
    override suspend fun sources() = listOf(MediaSource("camera", "Camera", 1))
    override suspend fun assets(from: Set<String>, addedAfterEpochMs: Long): List<Asset> {
        looked++
        return listOf(Asset("l-1", utcMillis(2026, 9, 1, 12, 0, 0), originalName = "IMG_1.jpg", sizeBytes = 3))
    }
    override suspend fun open(localId: String, part: AssetPart, from: Long): RawSource =
        Buffer().apply { write(byteArrayOf(1, 2, 3)) }
}

private class Picks : PickedFiles {
    val opened = mutableListOf<Pair<String, Long>>()
    val forgotten = mutableListOf<String>()
    val left = mutableListOf<PickedFile>()
    override suspend fun shared() = left.toList().also { left.clear() }
    override suspend fun open(id: String, from: Long): RawSource {
        opened += id to from
        return Buffer().apply { write(ByteArray(10), from.toInt(), 10) }
    }
    override suspend fun forget(id: String) {
        forgotten += id
    }
}

private class ScriptedTransport(
    override val resumable: Boolean,
    private val answers: MutableList<UploadOutcome>,
) : Transport {
    val sent = mutableListOf<Pair<UploadTarget, Resume?>>()
    override suspend fun send(
        target: UploadTarget,
        resume: Resume?,
        body: UploadBody,
        begun: suspend (Resume) -> Unit,
    ): UploadOutcome {
        sent += target to resume
        body.open(resume?.offset ?: 0).close()
        return if (answers.size > 1) answers.removeFirst() else answers.first()
    }
}

class HandUploadTest {

    private val database = BackupDatabase(BundledSQLiteDriver().open(":memory:"))
    private val picks = Picks()
    private val made = mutableListOf<String>()
    private val asked = mutableListOf<String>()
    private var occupied: (String) -> Boolean = { false }
    private val pickedAt = utcMillis(2026, 10, 2, 9, 0, 0)

    private val scan = PickedFile("content://scan", "scan.pdf", 10, "application/pdf")

    private suspend fun queue(transport: Transport): UploadQueue {
        database.migrate()
        return UploadQueue(
            layout = RemoteLayout(),
            pending = database.pending(),
            cache = database.cache(),
            source = WatchedRoll(),
            transport = transport,
            directories = { made += it },
            now = { 1_000 },
            picked = picks,
            taken = { path -> asked += path; occupied(path) },
        )
    }

    private suspend fun pick(vararg files: PickedFile, into: String = "/files/docs/") =
        database.pending().enqueueByHand(into, files.toList(), pickedAt)

    @Test
    fun aFilePickedByHandGoesWhereItWasPickedAndIsSettled() = runTest {
        val transport = ScriptedTransport(false, mutableListOf(UploadOutcome.Done("e")))
        val queue = queue(transport)
        pick(scan)

        // Said to be one picked by hand, which is what the notification names (stratus-app#258).
        assertEquals(QueueStep.Uploaded("files/docs/scan.pdf", byHand = true), queue.runNext())
        assertEquals("files/docs/scan.pdf", transport.sent.single().first.path)
        assertEquals(listOf("content://scan" to 0L), picks.opened)
        // Settled like a photograph: one table, one rule.
        assertTrue(database.cache().has("files/docs/scan.pdf"))
        assertEquals(listOf("content://scan"), picks.forgotten, "the grant outlived the upload")
        // Asked for, since a share's month may not exist yet (stratus-app#128).
        assertEquals(listOf("files/docs/"), made)
    }

    @Test
    fun aFolderOneSegmentDeepIsNeverAskedToBeMade() = runTest {
        // On a Stratus that is files/ itself, which answers neither 201 nor 405.
        val transport = ScriptedTransport(false, mutableListOf(UploadOutcome.Done(null)))
        val queue = queue(transport)
        pick(scan, into = "/files/")

        assertEquals(QueueStep.Uploaded("files/scan.pdf", byHand = true), queue.runNext())
        assertEquals(emptyList(), made)
    }

    @Test
    fun whatWasPickedGoesBeforeTheCameraRoll() = runTest {
        val transport = ScriptedTransport(false, mutableListOf(UploadOutcome.Done(null)))
        val queue = queue(transport)
        queue.enqueue(listOf(Asset("l-1", utcMillis(2026, 10, 1, 12, 0, 0), originalName = "IMG_1.jpg", sizeBytes = 3)))
        pick(scan)

        queue.runNext()

        assertEquals("files/docs/scan.pdf", transport.sent.first().first.path)
    }

    @Test
    fun theBackupsHalfOfAPassLeavesWhatWasPickedAlone() = runTest {
        // stratus-app#258: a hand upload sent by the backup's loop was counted
        // in its "sent" and shown as its current file.
        val transport = ScriptedTransport(false, mutableListOf(UploadOutcome.Done(null)))
        val queue = queue(transport)
        queue.enqueue(listOf(Asset("l-1", utcMillis(2026, 10, 1, 12, 0, 0), originalName = "IMG_1.jpg", sizeBytes = 3)))
        pick(scan)

        val step = queue.runNext(Origin.Library)

        assertTrue(step is QueueStep.Uploaded && !step.byHand, "was $step")
        assertEquals(1, queue.left(Origin.Picked))
        assertEquals(0, queue.left(Origin.Library))
    }

    @Test
    fun aNameTakenOnTheServerLandsBesideItOverPut() = runTest {
        val transport = ScriptedTransport(false, mutableListOf(UploadOutcome.Taken, UploadOutcome.Done(null)))
        val queue = queue(transport)
        pick(scan)

        val first = queue.runNext()
        assertTrue(first is QueueStep.Retrying && first.inMillis == 0L, "was $first")
        assertEquals(QueueStep.Uploaded("files/docs/scan (2).pdf", byHand = true), queue.runNext())

        assertEquals(listOf("files/docs/scan.pdf", "files/docs/scan (2).pdf"), transport.sent.map { it.first.path })
        assertTrue(transport.sent.all { it.first.exclusive }, "a PUT that may replace was sent")
        assertEquals(0, database.pending().all().size)
        assertTrue(asked.isEmpty(), "a PUT asks with its own precondition, not a PROPFIND")
    }

    @Test
    fun overTusTheFolderIsAskedBeforeTheUploadIsCreated() = runTest {
        occupied = { it == "files/docs/scan.pdf" }
        val transport = ScriptedTransport(true, mutableListOf(UploadOutcome.Done(null)))
        val queue = queue(transport)
        pick(scan)

        assertEquals(QueueStep.Uploaded("files/docs/scan (2).pdf", byHand = true), queue.runNext())
        assertEquals(listOf("files/docs/scan.pdf", "files/docs/scan (2).pdf"), asked)
        assertFalse(transport.sent.single().first.exclusive)
    }

    @Test
    fun aResumeNeverRenames() = runTest {
        val transport = ScriptedTransport(
            true,
            mutableListOf(UploadOutcome.Interrupted(Resume("http://host/tus/1", 4)), UploadOutcome.Done(null)),
        )
        val queue = queue(transport)
        pick(scan)
        queue.runNext()
        val claimed = database.pending().all().single()
        assertEquals("files/docs/scan.pdf", claimed.path)
        assertNull(claimed.numbered, "the name is the row's once the upload exists")

        // Whatever the folder holds now, the upload already has its name.
        occupied = { true }
        asked.clear()
        assertEquals(QueueStep.Uploaded("files/docs/scan.pdf", byHand = true), queue.runNext())
        assertEquals(emptyList(), asked)
        assertEquals(Resume("http://host/tus/1", 4), transport.sent.last().second)
        assertEquals("content://scan" to 4L, picks.opened.last())
    }

    @Test
    fun twoFilesOfOneNameInOneFolderAreTwoRows() = runTest {
        database.migrate()
        val paths = pick(scan, scan.copy(id = "content://other"))
        assertEquals(listOf("files/docs/scan.pdf", "files/docs/scan (2).pdf"), paths)
        assertEquals(2, database.pending().all(Origin.Picked).size)
    }

    @Test
    fun numbersTheWayTheServerDoes() {
        assertEquals("sunset.jpg", copyName("sunset.jpg", 1))
        assertEquals("sunset (2).jpg", copyName("sunset.jpg", 2))
        assertEquals("README (3)", copyName("README", 3))
        assertEquals("archive.tar (2).gz", copyName("archive.tar.gz", 2))
        assertEquals("v1.2/notes (2)", copyName("v1.2/notes", 2), "a dot in a folder is not an extension")
        assertEquals("a/scan (2) (2).pdf", copyName("a/scan (2).pdf", 2))
        assertEquals("a/scan (2).pdf", originalName("a/scan (2) (2).pdf", 2))
        assertEquals("a/scan.pdf", originalName("a/scan (7).pdf", 7))
    }

    @Test
    fun aPreconditionIsOnlyTakenForAWriteThatAskedForOne() {
        val claiming = PendingUpload("a", "l", AssetPart.Still, 1, null, "t", origin = Origin.Picked, numbered = 1)
        assertEquals(UploadOutcome.Taken, outcomeOf(claiming, TransferAnswer(412)))
        assertTrue(outcomeOf(claiming.copy(numbered = null), TransferAnswer(412)) is UploadOutcome.Failed)
    }

    @Test
    fun theBackupStatusDoesNotCountWhatWasPickedByHand() = runTest {
        database.migrate()
        pick(scan)
        val status = BackupStatus(database, WatchedRoll(), now = { 1_000 }, passing = { false }).of()
        assertEquals(BackupState.NeverRun, status)
    }

    @Test
    fun aQueueFromBeforeHandUploadsIsKeptAndReadAsTheCameraRoll() = runTest {
        // The schema as 7 left it, which is added to rather than rewritten.
        val connection = BundledSQLiteDriver().open(":memory:")
        connection.execSQL(
            """
            CREATE TABLE pending (
                path TEXT PRIMARY KEY, local_id TEXT NOT NULL, part TEXT NOT NULL, size INTEGER NOT NULL,
                content_type TEXT, taken_at TEXT NOT NULL, offset_at INTEGER NOT NULL DEFAULT 0, handle TEXT,
                attempts INTEGER NOT NULL DEFAULT 0, next_at INTEGER NOT NULL DEFAULT 0, last_error TEXT, ticket TEXT
            )
            """.trimIndent(),
        )
        for (table in listOf("settled (path TEXT PRIMARY KEY)", "journal (id INTEGER PRIMARY KEY)", "marks (id INTEGER PRIMARY KEY)")) {
            connection.execSQL("CREATE TABLE $table")
        }
        connection.execSQL("INSERT INTO pending (path, local_id, part, size, taken_at) VALUES ('p/a.jpg', 'l', 'Still', 3, 't')")
        connection.execSQL("PRAGMA user_version = 7")

        val row = BackupDatabase(connection).pending().all().single()
        assertEquals("p/a.jpg", row.path)
        assertEquals(Origin.Library, row.origin)
        assertNull(row.numbered)
    }

    // ---- A pass, against a server (stratus-app#127) -------------------------

    private val store = HandStore()
    private val instances = ServerStore(store)
    private val requests = mutableListOf<HttpRequestData>()
    private var tus = false

    /** Paths the server holds, as the client would write them. */
    private val holds = mutableSetOf<String>()
    private val folders = mutableSetOf("/files", "/files/docs")

    private val connections = Connections { instance ->
        val engine = MockEngine { request ->
            requests += request
            val path = DavPath.decode(request.url.encodedPath)
            when (request.method.value) {
                "OPTIONS" -> if (tus) {
                    respond("", HttpStatusCode.NoContent, headersOf("Tus-Resumable", "1.0.0"))
                } else {
                    respond("", HttpStatusCode.MethodNotAllowed)
                }
                "PROPFIND" -> if (path in holds) {
                    respond(
                        """<multistatus xmlns="DAV:"><response><href>${request.url.encodedPath}</href>""" +
                            """<propstat><prop><resourcetype/></prop><status>HTTP/1.1 200 OK</status></propstat></response></multistatus>""",
                        HttpStatusCode.MultiStatus,
                    )
                } else {
                    respond("", HttpStatusCode.NotFound)
                }
                "MKCOL" -> when {
                    path.trimEnd('/') in folders -> respond("", HttpStatusCode.MethodNotAllowed)
                    path.trimEnd('/').substringBeforeLast('/') in folders -> {
                        folders += path.trimEnd('/')
                        respond("", HttpStatusCode.Created)
                    }
                    else -> respond("", HttpStatusCode.Conflict)
                }
                "PUT" -> if (path in holds && request.headers["If-None-Match"] == "*") {
                    respond("", HttpStatusCode.PreconditionFailed)
                } else {
                    holds += path
                    respond("", HttpStatusCode.Created)
                }
                "POST" -> respond("", HttpStatusCode.Created, headersOf("Location", "/tus/abc"))
                "PATCH" -> respond("", HttpStatusCode.NoContent, headersOf("Upload-Offset", "10"))
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val http = HttpClient(engine)
        Connection(http, DavClient(http, instance.baseUrl))
    }

    private val roll = WatchedRoll()
    private val backup = Backup(instances, database, roll, connections, picked = picks, now = { pickedAt })

    private suspend fun signedIn(sources: Set<String> = emptySet()) {
        instances.put(Server("https://host/", "edu", sources = sources), Credentials("edu", "secret"))
    }

    private fun puts() = requests.filter { it.method.value == "PUT" }.map { DavPath.decode(it.url.encodedPath) }

    @Test
    fun withBackupOffAPassSendsWhatWasPickedAndNeverLooksAtTheRoll() = runTest {
        signedIn()
        backup.uploadByHand("/files/docs/", listOf(scan))
        assertTrue(backup.owed(), "a worker would have found nothing to do")

        assertEquals(PassOutcome.Finished, backup.pass())

        assertEquals(listOf("/files/docs/scan.pdf"), puts())
        assertEquals(0, roll.looked, "the camera roll was enumerated with backup off")
        assertEquals(emptyList(), backup.handUploads())
        assertFalse(backup.owed())
        assertEquals(1, backup.arrivals.value, "the folder would not have been told")
    }

    @Test
    fun overPutATakenNameIsRefusedAndTheNextCopyIsSent() = runTest {
        signedIn()
        holds += "/files/docs/scan.pdf"
        backup.uploadByHand("/files/docs/", listOf(scan))

        backup.pass()

        assertEquals(listOf("/files/docs/scan.pdf", "/files/docs/scan (2).pdf"), puts())
        assertTrue(requests.filter { it.method.value == "PUT" }.all { it.headers["If-None-Match"] == "*" })
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun overTusATakenNameIsFoundByAskingTheFolder() = runTest {
        tus = true
        signedIn()
        holds += "/files/docs/scan.pdf"
        backup.uploadByHand("/files/docs/", listOf(scan))

        backup.pass()

        val metadata = requests.single { it.method.value == "POST" }.headers["Upload-Metadata"].orEmpty()
        val filename = metadata.split(",").first { it.startsWith("filename ") }.removePrefix("filename ")
        assertEquals("files/docs/scan (2).pdf", Base64.decode(filename).decodeToString())
        assertEquals(emptyList(), puts())
    }

    @Test
    fun withBackupOnWhatWasPickedStillGoesFirst() = runTest {
        signedIn(sources = setOf("camera"))
        backup.uploadByHand("/files/docs/", listOf(scan))

        backup.pass()

        assertEquals("/files/docs/scan.pdf", puts().first())
        assertEquals(1, roll.looked)
    }

    // ---- The share sheet (stratus-app#128) ----------------------------------

    @Test
    fun aShareLandsInTheMonthBesideTheBackupMadeOnTheWay() = runTest {
        signedIn()
        val photo = PickedFile("content://photo", "", 10, "image/jpeg")

        assertEquals(ShareOutcome.Queued("files/shared/2026/10", 2), backup.share(listOf(scan, photo)))
        backup.pass()

        assertEquals(
            setOf("/files/shared/2026/10/scan.pdf", "/files/shared/2026/10/2026-10-02 09-00-00.jpg"),
            puts().toSet(),
        )
        assertTrue("/files/shared/2026/10" in folders, "the month was never made")
    }

    @Test
    fun aShareNeverReplaces() = runTest {
        signedIn()
        folders += listOf("/files/shared", "/files/shared/2026", "/files/shared/2026/10")
        holds += "/files/shared/2026/10/scan.pdf"

        backup.share(listOf(scan))
        backup.pass()

        assertEquals("/files/shared/2026/10/scan (2).pdf", puts().last())
    }

    @Test
    fun aShareWithNobodySignedInIsLetGoOf() = runTest {
        assertEquals(ShareOutcome.SignedOut, backup.share(listOf(scan)))
        assertEquals(listOf("content://scan"), picks.forgotten)
        assertEquals(emptyList(), backup.handUploads())
    }

    @Test
    fun aShareOfNoFileIsNotAFile() = runTest {
        signedIn()
        assertEquals(ShareOutcome.NotAFile, backup.share(emptyList()))
    }

    @Test
    fun theSharesFolderIsBesideTheBackupsWhereverThatIs() {
        val at = utcMillis(2026, 1, 31, 23, 59, 59)
        assertEquals("files/shared/2026/01", shareFolder("files/phone_backup", at))
        assertEquals("shared/2026/01", shareFolder("/phone/", at))
        assertEquals("a/b/shared/2026/01", shareFolder("a/b/c", at))
    }

    @Test
    fun aNamelessShareIsNamedAsTheServerWouldNameIt() {
        val at = utcMillis(2026, 1, 31, 23, 59, 59)
        assertEquals("2026-01-31 23-59-59.jpg", sharedName(null, "image/jpeg", at))
        assertEquals("2026-01-31 23-59-59.mp4", sharedName("  ", "video/mp4; codecs=avc1", at))
        assertEquals("2026-01-31 23-59-59", sharedName(null, "application/x-thing", at))
        assertEquals("note.txt", sharedName("dir/note.txt", "text/plain", at))
    }

    @Test
    fun whatAShareExtensionLeftIsTakenInByTheNextPass() = runTest {
        signedIn()
        picks.left += scan

        backup.pass()

        assertEquals(listOf("/files/shared/2026/10/scan.pdf"), puts())
        assertEquals(PassOutcome.Finished, backup.pass())
        assertEquals(1, puts().size, "a share was taken in twice")
    }
}
