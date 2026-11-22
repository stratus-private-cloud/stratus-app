package dev.stratus.core.files

import dev.stratus.core.backup.AssetPart
import dev.stratus.core.backup.Origin
import dev.stratus.core.backup.PendingUpload
import dev.stratus.core.dav.DavClient
import dev.stratus.core.dav.DavResource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.io.Sink
import kotlin.test.Test
import kotlin.test.assertEquals

private class NoHandoff : FileHandoff {
    override suspend fun open(name: String, contentType: String?, body: suspend (Sink) -> Unit) {}
    override suspend fun save(name: String, contentType: String?, body: suspend (Sink) -> Unit) {}
    override suspend fun send(name: String, contentType: String?, body: suspend (Sink) -> Unit) {}
}

private class Uploading : HandUploads {
    val going = MutableStateFlow<List<HandUpload>>(emptyList())
    val cancelled = MutableStateFlow<List<String>>(emptyList())
    override fun into(folder: String): Flow<List<HandUpload>> =
        going.map { all -> all.filter { it.path.substringBeforeLast('/').trim('/') == folder.trim('/') } }
    override suspend fun cancel(path: String) {
        cancelled.value = cancelled.value + path
    }
}

class HandUploadsTest {

    private fun row(path: String, takenAt: String = "1", nextAt: Long = 0, ticket: String? = null, error: String? = null) =
        PendingUpload(
            path, "id", AssetPart.Still, 100, null, takenAt,
            offset = 0, ticket = ticket, lastError = error, origin = Origin.Picked, nextAt = nextAt,
        )

    @Test
    fun aFolderShowsWhatIsGoingIntoItAndNothingElse() {
        val rows = listOf(row("files/docs/a.pdf", "2"), row("files/docs/b.pdf", "1"), row("files/docs/sub/c.pdf"))
        val shown = handUploadsInto("/files/docs/", rows, emptyMap(), now = 10)
        assertEquals(listOf("b.pdf", "a.pdf"), shown.map { it.name }, "in the order they were picked")
    }

    @Test
    fun saysHowFarAndWhatIsWrong() {
        val rows = listOf(
            row("d/going.pdf"),
            row("d/handed.pdf", ticket = "t"),
            row("d/later.pdf", nextAt = 50, error = "timeout"),
            row("d/never.pdf", nextAt = Long.MAX_VALUE, error = "forbidden"),
            row("d/queued.pdf"),
        )
        val shown = handUploadsInto("d", rows, mapOf("d/going.pdf" to 40L), now = 10).associateBy { it.name }
        assertEquals(HandUploadState.Sending, shown.getValue("going.pdf").state)
        assertEquals(40, shown.getValue("going.pdf").sent)
        assertEquals(HandUploadState.Sending, shown.getValue("handed.pdf").state)
        assertEquals(HandUploadState.Retrying("timeout"), shown.getValue("later.pdf").state)
        assertEquals(HandUploadState.Failed("forbidden"), shown.getValue("never.pdf").state)
        assertEquals(HandUploadState.Waiting, shown.getValue("queued.pdf").state)
    }

    @Test
    fun theRowBecomesTheFileWhenItIsSent() = runTest {
        // A flow and not a counter: the mock engine answers off the test
        // dispatcher, so there is nothing to advance, only something to wait for.
        val listings = MutableStateFlow(0)
        val engine = MockEngine { request ->
            if (request.method.value == "PROPFIND") listings.value++
            respond(
                """<multistatus xmlns="DAV:"><response><href>/docs/</href><propstat><prop><resourcetype><collection/></resourcetype></prop>""" +
                    """<status>HTTP/1.1 200 OK</status></propstat></response></multistatus>""",
                HttpStatusCode.MultiStatus,
            )
        }
        val uploads = Uploading()
        val browser = BrowserController(
            DavClient(HttpClient(engine), "http://host/"),
            NoHandoff(),
            backgroundScope,
            uploads = uploads,
        )
        browser.enter(DavResource("/docs", isDirectory = true))
        browser.state.first { !it.busy }
        val before = listings.value

        val going = HandUpload("docs/scan.pdf", 100, 40, HandUploadState.Sending)
        uploads.going.value = listOf(going)
        assertEquals(listOf(going), browser.state.first { it.uploads.isNotEmpty() }.uploads)

        uploads.going.value = emptyList()
        browser.state.first { it.uploads.isEmpty() }
        listings.first { it > before }

        browser.cancelUpload(going)
        assertEquals(listOf("docs/scan.pdf"), uploads.cancelled.first { it.isNotEmpty() })
    }
}
