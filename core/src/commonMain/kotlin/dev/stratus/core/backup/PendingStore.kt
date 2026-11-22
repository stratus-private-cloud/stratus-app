package dev.stratus.core.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.SQLiteStatement
import dev.stratus.core.sql.use
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Everything outstanding, counted in one query rather than by reading it all. */
data class PendingSummary(
    val total: Int,
    /** Given up on: a permanent failure, which will never be picked up again. */
    val givenUp: Int,
    /**
     * Handed to the system and not yet answered for.
     *
     * Still outstanding and counted in [total], because it is owed until the
     * server has it -- but not waiting on us, which is the difference a screen
     * has to be able to say (stratus-app#20).
     */
    val inFlight: Int = 0,
    val bytesLeft: Long,
    /** When the earliest waiting item becomes runnable, or null if one already is. */
    val nextAttemptAt: Long?,
    /** What the server said about something given up on, for a screen to quote. */
    val givenUpDetail: String? = null,
    /** What [inFlight] has left to send, for the budget a pass hands over to (stratus-app#233). */
    val inFlightBytes: Long = 0,
)

/** One outstanding piece of work: one part of one asset. */
data class PendingUpload(
    val path: String,
    val localId: String,
    val part: AssetPart,
    val size: Long,
    val contentType: String?,
    val takenAt: String,
    val offset: Long = 0,
    val handle: String? = null,
    val attempts: Int = 0,
    val lastError: String? = null,
    /**
     * What the platform calls the transfer it is carrying, where one is.
     *
     * Set only by a transport that hands work to the system and answers later
     * (stratus-app#20). A row with a ticket is not offered again -- see
     * [PendingStore.next] -- because the alternative is uploading the same
     * photograph twice.
     */
    val ticket: String? = null,
    /** Where the bytes are (stratus-app#127). */
    val origin: Origin = Origin.Library,
    /**
     * For a file picked by hand whose name is not settled yet, which numbered
     * copy of the name [path] is trying: 1 for the name itself, 2 for ` (2)`.
     * Null once the name is the row's for good, and always for the camera
     * roll, whose paths are derived and must not move.
     */
    val numbered: Int? = null,
    /** When it may be tried again; `Long.MAX_VALUE` is never. */
    val nextAt: Long = 0,
)

/**
 * The work still to do, kept where a restart cannot lose it.
 */
class PendingStore(
    private val connection: SQLiteConnection,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    /**
     * Adds work, leaving alone anything already queued.
     *
     * `INSERT OR IGNORE` rather than `REPLACE`: a second enqueue must not throw
     * away the offset an upload already reached, or a large video restarts every
     * time the app looks at the camera roll.
     */
    /**
     * Queues all of [uploads] in one transaction and answers how many were new:
     * one already queued is ignored, so a large video half sent is not reset,
     * and is not counted either -- the log's "queued" said otherwise
     * (stratus-app#240). One transaction rather than one per row, which is a
     * first pass over a camera roll of thousands.
     */
    suspend fun addAll(uploads: List<PendingUpload>): Int = withContext(io) {
        if (uploads.isEmpty()) return@withContext 0
        val before = count()
        connection.execSQL("BEGIN")
        try {
            for (upload in uploads) insert(upload)
            connection.execSQL("COMMIT")
        } catch (e: Throwable) {
            connection.execSQL("ROLLBACK")
            throw e
        }
        count() - before
    }

    private fun count(): Int = connection.prepare("SELECT COUNT(*) FROM pending").use { statement ->
        statement.step()
        statement.getInt(0)
    }

    suspend fun add(upload: PendingUpload) = withContext(io) { insert(upload) }

    private fun insert(upload: PendingUpload) {
        connection.prepare(
            """
            INSERT OR IGNORE INTO pending
                (path, local_id, part, size, content_type, taken_at, origin, numbered)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.bindText(1, upload.path)
            statement.bindText(2, upload.localId)
            statement.bindText(3, upload.part.name)
            statement.bindLong(4, upload.size)
            if (upload.contentType == null) statement.bindNull(5) else statement.bindText(5, upload.contentType)
            statement.bindText(6, upload.takenAt)
            statement.bindText(7, upload.origin.name)
            if (upload.numbered == null) statement.bindNull(8) else statement.bindLong(8, upload.numbered.toLong())
            statement.step()
        }
    }

    /**
     * The next thing worth attempting, newest photograph first.
     *
     * Newest first because the picture somebody just took is the one they check
     * for; a first backup that starts in 2014 looks broken for days. Anything
     * waiting out a backoff is skipped rather than blocking what is behind it.
     */
    suspend fun next(now: Long, origin: Origin? = null): PendingUpload? = withContext(io) {
        connection.prepare(
            """
            SELECT $COLUMNS
            FROM pending WHERE next_at <= ? AND ticket IS NULL AND (? IS NULL OR origin = ?)
            ORDER BY taken_at DESC, path ASC LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.bindLong(1, now)
            bindOrigin(statement, 2, origin)
            if (!statement.step()) return@withContext null
            read(statement)
        }
    }

    suspend fun recordProgress(path: String, resume: Resume) = withContext(io) {
        connection.prepare("UPDATE pending SET offset_at = ?, handle = ? WHERE path = ?")
            .use { statement ->
                statement.bindLong(1, resume.offset)
                if (resume.handle == null) statement.bindNull(2) else statement.bindText(2, resume.handle)
                statement.bindText(3, path)
                statement.step()
            }
        Unit
    }

    suspend fun recordFailure(path: String, detail: String, nextAt: Long) = withContext(io) {
        connection.prepare(
            "UPDATE pending SET attempts = attempts + 1, last_error = ?, next_at = ? WHERE path = ?",
        ).use { statement ->
            statement.bindText(1, detail)
            statement.bindLong(2, nextAt)
            statement.bindText(3, path)
            statement.step()
        }
        Unit
    }

    /**
     * Notes that the system is carrying this one, so nothing picks it up again.
     *
     * The offset goes in with it: a transport that hands over says where it
     * believes the server is, and the row has to agree or a resume starts from
     * the wrong place.
     */
    suspend fun recordHandover(path: String, ticket: String, resume: Resume?) = withContext(io) {
        connection.prepare(
            "UPDATE pending SET ticket = ?, offset_at = ?, handle = ? WHERE path = ?",
        ).use { statement ->
            statement.bindText(1, ticket)
            statement.bindLong(2, resume?.offset ?: 0)
            if (resume?.handle == null) statement.bindNull(3) else statement.bindText(3, resume.handle)
            statement.bindText(4, path)
            statement.step()
        }
        Unit
    }

    /** Whoever the system has just reported about, or null if nobody. */
    suspend fun byTicket(ticket: String): PendingUpload? = withContext(io) {
        connection.prepare(
            """
            SELECT $COLUMNS
            FROM pending WHERE ticket = ? LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.bindText(1, ticket)
            if (!statement.step()) return@withContext null
            read(statement)
        }
    }

    /** Back on the queue, for a result that is never going to arrive. */
    suspend fun release(path: String) = withContext(io) {
        connection.prepare("UPDATE pending SET ticket = NULL WHERE path = ?").use { statement ->
            statement.bindText(1, path)
            statement.step()
        }
        Unit
    }

    /**
     * Frees every row the platform has forgotten about, and answers how many.
     *
     * Done by reading rather than by a `NOT IN` list, because the set comes
     * from the platform and may be empty -- and an empty `IN ()` is a syntax
     * error in SQLite, which would turn "nothing is in flight" into a crash.
     */
    suspend fun releaseExcept(live: Set<String>): Int {
        val stale = all().filter { it.ticket != null && it.ticket !in live }
        for (upload in stale) release(upload.path)
        return stale.size
    }

    suspend fun remove(path: String) = withContext(io) {
        connection.prepare("DELETE FROM pending WHERE path = ?").use { statement ->
            statement.bindText(1, path)
            statement.step()
        }
        Unit
    }

    suspend fun all(): List<PendingUpload> = withContext(io) {
        val found = mutableListOf<PendingUpload>()
        connection.prepare(
            """
            SELECT $COLUMNS
            FROM pending ORDER BY taken_at DESC, path ASC
            """.trimIndent(),
        ).use { statement ->
            while (statement.step()) found += read(statement)
        }
        found
    }

    /**
     * The whole outstanding picture in one query.
     *
     * Counted here rather than by reading every row, because the status strip
     * asks this every second while somebody is looking at it and there may be
     * forty thousand rows behind the answer.
     */
    suspend fun summary(now: Long, origin: Origin? = null): PendingSummary = withContext(io) {
        connection.prepare(
            """
            SELECT COUNT(*),
                   SUM(CASE WHEN next_at >= ? THEN 1 ELSE 0 END),
                   SUM(CASE WHEN ticket IS NOT NULL THEN 1 ELSE 0 END),
                   COALESCE(SUM(size - offset_at), 0),
                   MIN(CASE WHEN next_at < ? THEN next_at ELSE NULL END),
                   MAX(CASE WHEN next_at >= ? THEN last_error ELSE NULL END),
                   COALESCE(SUM(CASE WHEN ticket IS NOT NULL THEN size - offset_at ELSE 0 END), 0)
            FROM pending WHERE (? IS NULL OR origin = ?)
            """.trimIndent(),
        ).use { statement ->
            statement.bindLong(1, NEVER)
            statement.bindLong(2, NEVER)
            statement.bindLong(3, NEVER)
            bindOrigin(statement, 4, origin)
            if (!statement.step()) return@withContext PendingSummary(0, 0, 0, 0, null)
            PendingSummary(
                total = statement.getInt(0),
                givenUp = statement.getInt(1),
                inFlight = statement.getInt(2),
                bytesLeft = statement.getLong(3),
                nextAttemptAt = if (statement.isNull(4)) null else statement.getLong(4).takeIf { it > now },
                givenUpDetail = if (statement.isNull(5)) null else statement.getText(5),
                inFlightBytes = statement.getLong(6),
            )
        }
    }

    /**
     * What has been given up on, bounded.
     *
     * A screen shows a handful and a count; reading forty thousand rows to
     * display twenty of them is how a status display becomes the slow part of
     * an app whose whole job is elsewhere.
     */
    suspend fun failures(limit: Int, origin: Origin? = null): List<PendingUpload> = withContext(io) {
        val found = mutableListOf<PendingUpload>()
        connection.prepare(
            """
            SELECT $COLUMNS
            FROM pending WHERE next_at >= ? AND (? IS NULL OR origin = ?)
            ORDER BY taken_at DESC LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.bindLong(1, NEVER)
            bindOrigin(statement, 2, origin)
            statement.bindLong(4, limit.toLong())
            while (statement.step()) found += read(statement)
        }
        found
    }

    suspend fun size(): Long = withContext(io) {
        connection.prepare("SELECT COUNT(*) FROM pending").use { statement ->
            if (statement.step()) statement.getLong(0) else 0L
        }
    }

    private fun read(statement: SQLiteStatement) = PendingUpload(
        path = statement.getText(0),
        localId = statement.getText(1),
        part = AssetPart.valueOf(statement.getText(2)),
        size = statement.getLong(3),
        contentType = if (statement.isNull(4)) null else statement.getText(4),
        takenAt = statement.getText(5),
        offset = statement.getLong(6),
        handle = if (statement.isNull(7)) null else statement.getText(7),
        attempts = statement.getInt(8),
        lastError = if (statement.isNull(9)) null else statement.getText(9),
        ticket = if (statement.isNull(10)) null else statement.getText(10),
        origin = Origin.valueOf(statement.getText(11)),
        numbered = if (statement.isNull(12)) null else statement.getInt(12),
        nextAt = statement.getLong(13),
    )

    /** Every row of one [origin], newest first. */
    suspend fun all(origin: Origin): List<PendingUpload> = withContext(io) {
        val found = mutableListOf<PendingUpload>()
        connection.prepare(
            "SELECT $COLUMNS FROM pending WHERE origin = ? ORDER BY taken_at DESC, path ASC",
        ).use { statement ->
            statement.bindText(1, origin.name)
            while (statement.step()) found += read(statement)
        }
        found
    }

    suspend fun contains(path: String): Boolean = withContext(io) {
        connection.prepare("SELECT 1 FROM pending WHERE path = ?").use { statement ->
            statement.bindText(1, path)
            statement.step()
        }
    }

    /**
     * Moves a row to another path, and says which copy of its name that is --
     * null once the name is settled (stratus-app#127). Only ever a hand
     * upload: a camera roll's path is derived from the photograph.
     */
    suspend fun rename(from: String, to: String, numbered: Int?) = withContext(io) {
        connection.prepare("UPDATE pending SET path = ?, numbered = ? WHERE path = ?").use { statement ->
            statement.bindText(1, to)
            if (numbered == null) statement.bindNull(2) else statement.bindLong(2, numbered.toLong())
            statement.bindText(3, from)
            statement.step()
        }
        Unit
    }

    /** Both placeholders of an `(? IS NULL OR origin = ?)`, starting at [at]. */
    private fun bindOrigin(statement: SQLiteStatement, at: Int, origin: Origin?) {
        if (origin == null) {
            statement.bindNull(at)
            statement.bindNull(at + 1)
        } else {
            statement.bindText(at, origin.name)
            statement.bindText(at + 1, origin.name)
        }
    }

    private companion object {
        /** The marker the queue writes for work it will not try again. */
        const val NEVER = Long.MAX_VALUE

        const val COLUMNS =
            "path, local_id, part, size, content_type, taken_at, offset_at, handle, attempts, last_error, ticket, origin, numbered, next_at"
    }
}
