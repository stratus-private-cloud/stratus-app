package dev.stratus.core.backup

import dev.stratus.core.dav.DavClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AlreadyThereTest {

    private val target = UploadTarget("/files/phone_backup/2026/09/IMG_1.a1b2c3d4.heic", 10, null)

    private fun server(status: HttpStatusCode, size: Long = 10, collection: Boolean = false) =
        DavClient(
            HttpClient(
                MockEngine {
                    val body = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"><d:response>
                        <d:href>${target.path}</d:href><d:propstat><d:prop>
                        <d:resourcetype>${if (collection) "<d:collection/>" else ""}</d:resourcetype>
                        <d:getcontentlength>$size</d:getcontentlength><d:getetag>"e1"</d:getetag>
                        </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
                    respond(body, status, headersOf("Content-Type", "application/xml"))
                },
            ),
            "https://host/",
        )

    @Test
    fun theSamePhotographAlreadyThereIsDone() = runTest {
        // stratus-app#234: a finished transfer whose answer was lost was sent
        // again whole. Its path is a function of the photograph, so a file
        // there of its size is it.
        val done = alreadyThere(server(HttpStatusCode.MultiStatus), target)
        assertEquals("e1", done?.etag)
    }

    @Test
    fun anythingElseIsSent() = runTest {
        assertNull(alreadyThere(server(HttpStatusCode.MultiStatus, size = 9), target), "a partial file")
        assertNull(alreadyThere(server(HttpStatusCode.MultiStatus, collection = true), target), "a folder")
        assertNull(alreadyThere(server(HttpStatusCode.NotFound), target), "nothing there")
        assertNull(alreadyThere(server(HttpStatusCode.InternalServerError), target), "a server that did not say")
    }
}
