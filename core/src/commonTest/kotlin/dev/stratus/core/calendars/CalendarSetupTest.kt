package dev.stratus.core.calendars

import dev.stratus.core.net.Credentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CalendarSetupTest {

    private val credentials = Credentials("edu", "secret")

    @Test
    fun davx5IsGivenTheServerUnderItsOwnSchemes() {
        assertEquals("davx5://cloud.example/", davx5Uri("https://cloud.example/"))
        assertEquals("davx5://cloud.example:8443/", davx5Uri("https://cloud.example:8443/"))
        assertEquals("caldav://cloud.example/", davx5Uri("http://cloud.example"))
        assertEquals("caldav://192.168.1.2:8080/", davx5Uri("http://192.168.1.2:8080/"))
        // Somebody else's server keeps its path, which is where DAVx5 starts looking.
        assertEquals("davx5://nc.example/remote.php/dav/", davx5Uri("https://nc.example/remote.php/dav/"))
    }

    @Test
    fun theUriCarriesNoCredentials() {
        val setup = calendarSetup("https://cloud.example/", credentials, toDavx5 = true, stratusAtOrigin = false)
        assertEquals(CalendarSetup.Davx5("davx5://cloud.example/", credentials), setup)
        assertEquals(false, "secret" in (setup as CalendarSetup.Davx5).uri)
    }

    @Test
    fun aPageIsOnlyAStratusAtItsOrigin() {
        assertEquals(
            CalendarSetup.Page("https://cloud.example:8443/connect"),
            calendarSetup("https://cloud.example:8443/", credentials, toDavx5 = false, stratusAtOrigin = true),
        )
        assertNull(calendarSetup("https://nc.example/remote.php/dav/", credentials, toDavx5 = false, stratusAtOrigin = false))
    }
}
