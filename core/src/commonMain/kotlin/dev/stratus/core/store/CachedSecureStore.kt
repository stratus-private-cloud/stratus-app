package dev.stratus.core.store

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [SecureStore] that remembers what it read (stratus-app#236).
 *
 * A read underneath is a decrypt under the Android Keystore or a Keychain
 * query, and the server and its pins were read that way once a second for the
 * status strip and once per tile for the pictures. Every write in the process
 * goes through here, so what is remembered cannot go stale behind it; a File
 * Provider extension is another process, and it only reads.
 */
class CachedSecureStore(private val underneath: SecureStore) : SecureStore {
    private val lock = Mutex()
    private val kept = mutableMapOf<String, String?>()

    override suspend fun read(key: String): String? = lock.withLock {
        if (key in kept) return@withLock kept[key]
        underneath.read(key).also { kept[key] = it }
    }

    override suspend fun write(key: String, value: String) = lock.withLock {
        underneath.write(key, value)
        kept[key] = value
    }

    override suspend fun delete(key: String) = lock.withLock {
        underneath.delete(key)
        kept[key] = null
    }
}
