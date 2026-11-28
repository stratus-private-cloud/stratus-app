package dev.stratus.core.backup

import androidx.sqlite.SQLiteConnection
import dev.stratus.core.sql.use
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The folders something was last put into by hand, newest first, for the
 * share sheet to offer as destinations (stratus-app#269). A path from the
 * origin with no slashes at either end, as the queue writes one.
 */
class RecentFolders(
    private val connection: SQLiteConnection,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun list(): List<String> = withContext(io) {
        connection.prepare("SELECT path FROM recent_folders ORDER BY used_at DESC, path LIMIT $KEPT").use { statement ->
            buildList { while (statement.step()) add(statement.getText(0)) }
        }
    }

    suspend fun used(folder: String, at: Long) = withContext(io) {
        connection.prepare(
            "INSERT INTO recent_folders (path, used_at) VALUES (?, ?) " +
                "ON CONFLICT (path) DO UPDATE SET used_at = excluded.used_at",
        ).use { statement ->
            statement.bindText(1, folder.trim('/'))
            statement.bindLong(2, at)
            statement.step()
        }
        connection.prepare(
            "DELETE FROM recent_folders WHERE path NOT IN " +
                "(SELECT path FROM recent_folders ORDER BY used_at DESC, path LIMIT $KEPT)",
        ).use { statement -> statement.step() }
        Unit
    }

    companion object {
        const val KEPT = 4
    }
}
