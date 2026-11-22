package dev.stratus.core.backup

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * What a picked file is read as (stratus-app#127), against a real provider:
 * the name and size the resolver reports, and the bytes from an offset, which
 * is what a resumed upload asks for. A Downloads entry this process made,
 * which offers no persistable grant -- the case `describe` must survive.
 */
class AndroidPickedFilesTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val files = AndroidPickedFiles(context)
    private val inserted = mutableListOf<Uri>()

    private fun download(name: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        // Published once written, which is when the provider measures it.
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        inserted += uri
        return uri
    }

    @AfterTest
    fun tidyUp() {
        inserted.forEach { resolver.delete(it, null, null) }
    }

    @Test
    fun describesWhatWasPickedAndReadsItFromAnOffset() = runTest {
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        val name = "scan${Random.nextInt(100_000)}.pdf"
        val uri = download(name, bytes)

        val picked = files.describe(listOf(uri)).single()
        assertEquals(uri.toString(), picked.id)
        assertEquals(name, picked.name)
        assertEquals(bytes.size.toLong(), picked.size)
        assertEquals("application/pdf", picked.contentType)

        val rest = files.open(picked.id, 123_456).buffered().use { it.readByteArray() }
        assertEquals(bytes.size - 123_456, rest.size)
        assertEquals(bytes.copyOfRange(123_456, bytes.size).toList(), rest.toList())

        // Nothing was granted to release, which must be a shrug.
        files.forget(picked.id)
    }

    @Test
    fun aShareIsCopiedSoItOutlivesTheGrantAndIsDeletedWhenForgotten() = runTest {
        // stratus-app#128: an ACTION_SEND grant ends with the activity, so the
        // id must not be the sender's URI.
        val bytes = ByteArray(200_000) { (it % 241).toByte() }
        val name = "shared${Random.nextInt(100_000)}.pdf"
        val uri = download(name, bytes)

        val kept = files.keep(listOf(uri)).single()
        resolver.delete(uri, null, null)
        assertEquals(name, kept.name)
        assertEquals(bytes.size.toLong(), kept.size)

        val rest = files.open(kept.id, 99_999).buffered().use { it.readByteArray() }
        assertEquals(bytes.copyOfRange(99_999, bytes.size).toList(), rest.toList())

        files.forget(kept.id)
        assertFailsWith<Exception> { files.open(kept.id, 0) }
    }
}
