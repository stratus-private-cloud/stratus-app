package dev.stratus.core.backup

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The half of stratus-app#233 a simulator can settle: a body no live transfer
 * claims is deleted, one that is claimed and anything else are left alone.
 */
class SpoolSweepTest {

    private val dir = Path(NSTemporaryDirectory() + "sweep-" + NSUUID().UUIDString())

    private fun file(name: String): Path {
        SystemFileSystem.createDirectories(dir)
        val path = Path(dir, name)
        SystemFileSystem.sink(path).buffered().use { it.writeString("bytes") }
        return path
    }

    @AfterTest
    fun tidyUp() {
        SystemFileSystem.list(dir).forEach { SystemFileSystem.delete(it, mustExist = false) }
        SystemFileSystem.delete(dir, mustExist = false)
    }

    @Test
    fun aBodyNoTransferClaimsIsDeleted() {
        val orphan = file("stratus-upload-gone")
        val live = file("stratus-upload-carried")
        val other = file("somebody-elses.tmp")

        assertEquals(1, BackgroundUploads.sweep(dir, setOf("carried")))

        assertFalse(SystemFileSystem.exists(orphan))
        assertTrue(SystemFileSystem.exists(live))
        assertTrue(SystemFileSystem.exists(other))
    }
}
