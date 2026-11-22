package dev.stratus.core.store

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A store that counts what it is asked, which is what the cache is for. */
private class Counting : SecureStore {
    val kept = mutableMapOf<String, String>()
    var reads = 0
    override suspend fun read(key: String): String? {
        reads++
        return kept[key]
    }
    override suspend fun write(key: String, value: String) { kept[key] = value }
    override suspend fun delete(key: String) { kept.remove(key) }
}

class CachedSecureStoreTest {

    @Test
    fun aKeyIsReadFromUnderneathOnce() = runTest {
        // stratus-app#236: the status strip read the server once a second, and
        // every read was a decrypt under the Keystore.
        val underneath = Counting().apply { kept["server"] = "s" }
        val store = CachedSecureStore(underneath)
        repeat(10) { assertEquals("s", store.read("server")) }
        assertEquals(1, underneath.reads)
    }

    @Test
    fun aWriteIsWhatTheNextReadSees() = runTest {
        val underneath = Counting()
        val store = CachedSecureStore(underneath)
        store.read("server")
        store.write("server", "new")
        assertEquals("new", store.read("server"))
        assertEquals("new", underneath.kept["server"])
        assertEquals(1, underneath.reads)
    }

    @Test
    fun aDeleteIsAnAbsenceAndAnAbsenceIsRemembered() = runTest {
        val underneath = Counting().apply { kept["server"] = "s" }
        val store = CachedSecureStore(underneath)
        store.delete("server")
        assertNull(store.read("server"))
        assertNull(store.read("pins"))
        assertNull(store.read("pins"))
        assertEquals(1, underneath.reads, "the absence of pins was asked about twice")
    }
}
