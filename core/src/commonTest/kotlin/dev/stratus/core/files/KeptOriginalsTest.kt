package dev.stratus.core.files

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeptOriginalsTest {

    @Test
    fun theOldestGoesWhenTheBytesRunOver() = runTest {
        val kept = KeptOriginals(most = 10)
        kept.put("a", ByteArray(4))
        kept.put("b", ByteArray(4))
        kept.get("a") // a was looked at since, so b is the oldest
        kept.put("c", ByteArray(4))

        assertNull(kept.get("b"))
        assertEquals(4, kept.get("a")?.size)
        assertEquals(4, kept.get("c")?.size)
    }

    @Test
    fun oneLargerThanTheBoundIsStillKeptAlone() = runTest {
        // Or the photograph on screen would be downloaded again to swipe back to.
        val kept = KeptOriginals(most = 10)
        kept.put("a", ByteArray(4))
        kept.put("big", ByteArray(20))
        assertNull(kept.get("a"))
        assertEquals(20, kept.get("big")?.size)
        kept.clear()
        assertNull(kept.get("big"))
    }
}
