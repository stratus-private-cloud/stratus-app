package dev.stratus.core.files

import dev.stratus.core.dav.DavResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimelineOrderTest {
    @Test
    fun aWallClockIsReadAsUtc() {
        assertEquals(0L, wallClockEpochMs("1970-01-01T00:00:00"))
        assertEquals(1_763_649_022_000L, wallClockEpochMs("2025-11-20T14:30:22"))
        assertEquals(951_782_400_000L, wallClockEpochMs("2000-02-29T00:00:00"))
        assertNull(wallClockEpochMs("2025-11-20"))
    }

    /**
     * The web's order, which is the server's sort_at: the camera's time, the
     * arrival where there is none, and never the name -- a screenshot and a
     * photograph sent from another phone are named nothing like a backup.
     */
    @Test
    fun aMonthIsInTheOrderTheWebShowsIt() {
        val listing = listOf(
            DavResource("/photos/2025/11/2025-11-02_090000_IMG_1.abc.heic", false, taken = "2025-11-02T09:00:00"),
            DavResource("/photos/2025/11/Screenshot_20251110.png", false, lastModifiedEpochMs = wallClockEpochMs("2025-11-10T12:00:00")),
            DavResource("/photos/2025/11/IMG-20251105-WA0001.jpg", false, taken = "2025-11-05T20:00:00", lastModifiedEpochMs = wallClockEpochMs("2025-11-21T08:00:00")),
            DavResource("/photos/2025/11/2025-11-20_143022_IMG_2.def.heic", false, taken = "2025-11-20T14:30:22"),
            DavResource("/photos/2025/11/nothing.jpg", false),
        )
        assertEquals(
            listOf(
                "2025-11-20_143022_IMG_2.def.heic",
                "Screenshot_20251110.png",
                "IMG-20251105-WA0001.jpg",
                "2025-11-02_090000_IMG_1.abc.heic",
                "nothing.jpg",
            ),
            newestTakenFirst(listing).map { it.name },
        )
    }
}
