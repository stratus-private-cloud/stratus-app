package dev.stratus.core.media

import dev.stratus.core.dav.DavClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoriesTest {

    /** A file is a path and when it was taken; a folder is a path ending in a slash. */
    private fun listing(vararg entries: Pair<String, String?>) = buildString {
        append("""<multistatus xmlns="DAV:">""")
        for ((href, taken) in entries) {
            append("<response><href>$href</href><propstat><prop><resourcetype>")
            if (href.endsWith("/")) append("<collection/>")
            append("</resourcetype>")
            if (taken != null) append("""<taken xmlns="https://github.com/C0piIot/stratus">$taken</taken>""")
            append("</prop><status>HTTP/1.1 200 OK</status></propstat></response>")
        }
        append("</multistatus>")
    }

    private val asked = mutableListOf<String>()

    private fun dav(tree: Map<String, String>, failing: String? = null) = DavClient(
        HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                asked += path
                when {
                    path == failing -> respond("", HttpStatusCode.InternalServerError)
                    path in tree -> respond(tree.getValue(path), HttpStatusCode.MultiStatus)
                    else -> respond("", HttpStatusCode.NotFound)
                }
            },
        ),
        "http://host/",
    )

    private val library = mapOf(
        "/photos/" to listing("/photos/2026/" to null, "/photos/2024/" to null, "/photos/2010/" to null),
        "/photos/2024/" to listing("/photos/2024/03/" to null, "/photos/2024/11/" to null),
        "/photos/2024/11/" to listing(
            "/photos/2024/11/a.jpg" to "2024-11-02T09:00:00",
            "/photos/2024/11/b.jpg" to "2024-11-28T18:30:00",
        ),
        "/photos/2010/" to listing("/photos/2010/11/" to null),
        "/photos/2010/11/" to listing("/photos/2010/11/c.jpg" to "2010-11-05T12:00:00"),
    )

    @Test
    fun aPhotographTakenOnThisDayWinsOverTheRestOfTheMonth() = runTest {
        val memory = memoryOn(dav(library), 2026, 11, 28)
        assertEquals("/photos/2024/11/b.jpg", memory?.photo?.path)
        assertEquals("2 years ago today", memory?.caption)
    }

    @Test
    fun withNothingOnTheDayItIsOneFromTheMonth() = runTest {
        val memory = memoryOn(dav(library), 2026, 11, 15)!!
        assertTrue(memory.photo.path in setOf("/photos/2024/11/a.jpg", "/photos/2024/11/b.jpg", "/photos/2010/11/c.jpg"))
        assertTrue(memory.caption.startsWith("This month, "))
        // The same one all day.
        assertEquals(memory, memoryOn(dav(library), 2026, 11, 15))
    }

    @Test
    fun thisYearAndMoreThanTenYearsBackAreNotAskedFor() = runTest {
        memoryOn(dav(library), 2026, 11, 28)
        assertTrue(asked.none { it.startsWith("/photos/2026/") || it.startsWith("/photos/2010/") })
    }

    @Test
    fun aMonthNumberedWithALeadingZeroIsTheSameMonth() = runTest {
        val tree = mapOf(
            "/photos/" to listing("/photos/2025/" to null),
            "/photos/2025/" to listing("/photos/2025/03/" to null),
            "/photos/2025/03/" to listing("/photos/2025/03/d.jpg" to "2025-03-01T10:00:00"),
        )
        assertEquals("A year ago today", memoryOn(dav(tree), 2026, 3, 1)?.caption)
    }

    @Test
    fun aServerWithNoTimelineHasNoMemoryAndNoError() = runTest {
        assertNull(memoryOn(dav(emptyMap()), 2026, 11, 28))
        assertNull(memoryOn(dav(library), 2026, 7, 1))
    }

    @Test
    fun aFailureFurtherInIsThrownRatherThanTakenForNothing() = runTest {
        assertFailsWith<Exception> { memoryOn(dav(library, failing = "/photos/2024/11/"), 2026, 11, 28) }
    }
}
