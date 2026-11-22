package dev.stratus.core.files

import dev.stratus.core.backup.Backup
import dev.stratus.core.backup.PendingUpload
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/** A file picked by hand on its way into a folder, as that folder shows it (stratus-app#127). */
data class HandUpload(
    val path: String,
    val size: Long,
    /** How much of it has gone, as far as this process can tell. */
    val sent: Long,
    val state: HandUploadState,
) {
    val name: String get() = path.substringAfterLast('/')
}

sealed interface HandUploadState {
    /** Queued, and the next pass will take it. */
    data object Waiting : HandUploadState

    /** Going now, here or in the system's hands. */
    data object Sending : HandUploadState

    /** Refused or cut off, and waiting out a backoff before it is tried again. */
    data class Retrying(val detail: String?) : HandUploadState

    /** Refused for good: somebody has to take it away. */
    data class Failed(val detail: String?) : HandUploadState
}

/**
 * What is on its way into a folder, for the browser to draw as rows of their
 * own -- and not the backup screen, whose count is the camera roll's.
 */
interface HandUploads {
    /** What is going into [folder], again whenever it changes, for as long as it is collected. */
    fun into(folder: String): Flow<List<HandUpload>>

    /** Counts each upload that arrives, anywhere; a change is a reason to list the folder again. */
    val arrivals: Flow<Int>

    /** Takes one off the queue, which is the only way to be rid of one that failed. */
    suspend fun cancel(path: String)
}

/**
 * The queue's own rows, asked every second -- polled rather than pushed for
 * the reason the backup strip is: the pass writes the same table on another
 * thread, and reading it is the only way this cannot disagree with it.
 */
class QueuedHandUploads(
    private val backup: Backup,
    private val every: Long = 1_000,
    private val now: () -> Long = { getTimeMillis() },
) : HandUploads {
    override fun into(folder: String): Flow<List<HandUpload>> = flow {
        while (true) {
            val sent = backup.progress.value
            emit(handUploadsInto(folder, backup.handUploads(), sent, now()))
            delay(every)
        }
    }.distinctUntilChanged()

    override val arrivals: Flow<Int> = backup.arrivals

    override suspend fun cancel(path: String) = backup.cancelHandUpload(path)
}

/** The rows of [rows] going into [folder], oldest picked first, as a folder draws them. */
fun handUploadsInto(folder: String, rows: List<PendingUpload>, sent: Map<String, Long>, now: Long): List<HandUpload> {
    val here = folder.trim('/')
    return rows
        .filter { it.path.substringBeforeLast('/', "").trim('/') == here }
        .sortedWith(compareBy<PendingUpload> { it.takenAt }.thenBy { it.path })
        .map { row ->
            val state = when {
                row.nextAt == Long.MAX_VALUE -> HandUploadState.Failed(row.lastError)
                row.ticket != null || row.path in sent -> HandUploadState.Sending
                row.nextAt > now -> HandUploadState.Retrying(row.lastError)
                else -> HandUploadState.Waiting
            }
            HandUpload(row.path, row.size, sent[row.path] ?: row.offset, state)
        }
}
