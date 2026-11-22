package dev.stratus.core.backup

import dev.stratus.core.dav.DavError
import kotlinx.coroutines.CancellationException
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.io.Buffer
import kotlinx.io.RawSource

/** Makes a directory and whatever it hangs from, ignoring what is already there. */
fun interface DirectoryMaker {
    suspend fun ensure(path: String)
}

/** What one turn of the queue did. */
sealed interface QueueStep {
    data object Idle : QueueStep
    data class Uploaded(val path: String) : QueueStep
    data class Progressed(val path: String, val offset: Long) : QueueStep

    /** Given to the platform; the answer arrives through [UploadQueue.settle]. */
    data class HandedOver(val path: String) : QueueStep
    data class Retrying(val path: String, val inMillis: Long, val detail: String) : QueueStep
    data class GaveUp(val path: String, val detail: String) : QueueStep

    /**
     * The server never answered, so nothing was learned about any photograph.
     *
     * No path on purpose: it is not about a file, and the whole bug it exists
     * for was charging one for it (stratus-app#134).
     */
    data class Unreachable(val detail: String) : QueueStep
}

/**
 * What to send next, and what to do about what happened.
 *
 * One instance's worth. A photograph owed to two servers is two pieces of work
 * that succeed and fail apart, so an instance that is down cannot hold up the
 * other -- which is only true if nothing here knows about more than one.
 *
 * Every decision lives in this class and none of them in the transport, because
 * the transport is the part that will be a background `URLSession` on iOS: code
 * that runs where no test can watch it should not be deciding anything.
 */
class UploadQueue(
    private val layout: RemoteLayout,
    private val pending: PendingStore,
    private val cache: BackupCache,
    private val source: AssetSource,
    private val transport: Transport,
    private val directories: DirectoryMaker,
    private val now: () -> Long = { getTimeMillis() },
    /** Where the bytes of a file picked by hand are (stratus-app#127); null where nothing is picked. */
    private val picked: PickedFiles? = null,
    /**
     * Whether the server holds something at a path, asked before a hand
     * upload goes by tus: its creation has no `If-None-Match`, and the file
     * only appears, replacing whatever was there, once the last byte is in.
     */
    private val taken: suspend (String) -> Boolean = { false },
    /** How far each hand upload's bytes have been read, for the folder it is going into. */
    private val progress: MutableStateFlow<Map<String, Long>>? = null,
) {
    /** Queues whatever the server has not got. Returns how much was added. */
    suspend fun enqueue(assets: List<Asset>): Int {
        val known = cache.paths()
        val wanted = mutableListOf<PendingUpload>()
        for (asset in assets) {
            val parts = buildList {
                add(Part(AssetPart.Still, layout.pathFor(asset), asset.sizeBytes, asset.mimeType))
                asset.motion?.let { add(Part(AssetPart.Motion, layout.motionPathFor(asset)!!, it.sizeBytes, it.mimeType)) }
            }
            for ((part, path, size, type) in parts) {
                if (path in known) continue
                wanted.add(
                    PendingUpload(
                        path = path,
                        localId = asset.localId,
                        part = part,
                        size = size,
                        contentType = type,
                        takenAt = stampOf(asset),
                    ),
                )
            }
        }
        return pending.addAll(wanted)
    }

    /**
     * Takes one thing off the queue and tries it.
     *
     * One at a time, deliberately: on a phone, several at once drains the battery
     * and saturates the uplink without finishing any sooner.
     */
    suspend fun runNext(origin: Origin? = null): QueueStep {
        var upload = pending.next(now(), origin) ?: return QueueStep.Idle
        val byHand = upload.origin == Origin.Picked

        // The server refuses a write whose parent does not exist -- measured, a
        // 409 -- and tus is stricter still: its destination is a path and the
        // folder has to be there before the upload is even created.
        //
        // Which failure this is decides who pays for it. A server that answered
        // said something about *this path*, so the row takes it; a server that
        // never answered -- a timeout, a reset, no DNS -- said nothing about any
        // photograph, and charging one its attempt and its doubling backoff is
        // how a file reaches a thirty-two minute wait without ever having been
        // refused (stratus-app#134).
        //
        // Not for a file picked by hand, which goes into the folder somebody
        // was looking at: asking to make it is a request for nothing, and on a
        // Stratus the folder may be `files/` itself, which its router owns
        // and answers with neither 201 nor 405.
        try {
            if (!byHand) directories.ensure(upload.path.substringBeforeLast('/') + "/")
            // Once, before the first byte, and never on a resume: the name
            // travels in the upload's metadata, so a row with a handle has
            // already chosen it (stratus-app#127).
            if (byHand && upload.numbered != null && transport.resumable && upload.handle == null) {
                upload = claim(upload)
                    ?: return records.record(
                        upload,
                        UploadOutcome.Failed(FailureKind.Permanent, "$MOST_COPIES files already have this name"),
                    )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DavError) {
            return records.record(upload, UploadOutcome.Failed(kindOf(e), e.message ?: "the server refused"))
        } catch (e: Exception) {
            return QueueStep.Unreachable(e.message ?: "the server did not answer")
        }

        // Resuming asks the transport to continue, never the local offset alone:
        // a client can believe it is somewhere the server does not agree with,
        // which is exactly what Stratus answers with a 409.
        // The handle and not the offset: a transfer cut before its first
        // acknowledged byte still has an upload on the server to ask about.
        val resume = if (transport.resumable && upload.handle != null) {
            Resume(upload.handle, upload.offset)
        } else {
            null
        }

        val sending = upload
        val outcome = try {
            transport.send(
                UploadTarget(sending.path, sending.size, sending.contentType, exclusive = sending.numbered != null),
                resume,
                bodyOf(sending),
                begun = { pending.recordProgress(sending.path, it) },
            )
        } catch (e: CancellationException) {
            // The system stopping the pass is not the server refusing the file;
            // recorded as a failure it showed up in the Backup screen as one,
            // reading "Job was cancelled" (stratus-app#83).
            throw e
        } catch (e: Exception) {
            return records.record(
                upload,
                UploadOutcome.Failed(FailureKind.Transient, e.message ?: e::class.simpleName ?: "no detail"),
            )
        } finally {
            if (byHand) progress?.update { it - sending.path }
        }

        return records.record(upload, outcome)
    }

    /**
     * The first free copy of [upload]'s name, by asking for each in turn
     * until one is not there -- `scan.pdf`, then `scan (2).pdf` -- and the
     * row moved to it for good. Null past [MOST_COPIES].
     */
    private suspend fun claim(upload: PendingUpload): PendingUpload? {
        val from = upload.numbered ?: return upload
        val original = originalName(upload.path, from)
        for (n in from..MOST_COPIES) {
            val path = copyName(original, n)
            if (path != upload.path && pending.contains(path)) continue
            if (taken(path)) continue
            pending.rename(upload.path, path, null)
            return upload.copy(path = path, numbered = null)
        }
        return null
    }

    /**
     * Where a result is written down, whoever brings it (stratus-app#20).
     *
     * The queue owns one; the door a background transfer comes back through
     * builds another over the same two tables. Both end in the same code,
     * which is the point.
     */
    private val records = UploadRecords(pending, cache, now, picked)

    suspend fun settle(ticket: String, answer: TransferAnswer): QueueStep = records.settle(ticket, answer)

    suspend fun settle(ticket: String, outcome: UploadOutcome): QueueStep = records.settle(ticket, outcome)

    suspend fun reconcile(live: Set<String>): Int = records.reconcile(live)

    /** What is outstanding, counted. */
    suspend fun summary(): PendingSummary = pending.summary(now())

    /** Everything outstanding. Unbounded, so not for anything asked repeatedly. */
    suspend fun outstanding(): List<PendingUpload> = pending.all()

    /** How much is left, counted rather than read. */
    suspend fun left(): Int = summary().total

    /** One upload's bytes, either way a transport can take them, from wherever its row says they are. */
    private fun bodyOf(upload: PendingUpload): UploadBody = when (upload.origin) {
        Origin.Library -> object : UploadBody {
            override suspend fun open(from: Long) = source.open(upload.localId, upload.part, from)
            override suspend fun writeTo(from: Long, toPath: String) =
                source.writeTo(upload.localId, upload.part, from, toPath)
        }

        Origin.Picked -> object : UploadBody {
            private val files get() = picked ?: error("nothing here can read a file picked by hand")

            override suspend fun open(from: Long): RawSource {
                val source = files.open(upload.localId, from)
                val progress = progress ?: return source
                return Counted(source, from, upload.size) { at -> progress.update { it + (upload.path to at) } }
            }

            // Not counted: what this writes is a spool for the system to send,
            // and reading it says nothing about the server.
            override suspend fun writeTo(from: Long, toPath: String) = files.writeTo(upload.localId, from, toPath)
        }
    }

    /** Sortable, so "newest first" is an index scan rather than a decode. */
    private fun stampOf(asset: Asset): String = asset.capturedAtEpochMs.toString().padStart(14, '0')

}

private data class Part(val part: AssetPart, val path: String, val size: Long, val type: String?)

/**
 * Queues files picked by hand into [folder], newest of everything so a pass
 * sends them before the camera roll (stratus-app#127): somebody who just
 * pressed upload should not wait out a backup. Answers the paths they went in
 * under.
 *
 * The second way into the queue, beside [UploadQueue.enqueue], and apart from
 * it because it needs no connection: a file picked on a train is queued there.
 * The name is the file's own unless another row already holds it; whether the
 * server holds it is asked when it is sent, which is when the answer is true.
 */
suspend fun PendingStore.enqueueByHand(folder: String, files: List<PickedFile>, pickedAt: Long): List<String> {
    val under = folder.trim('/').let { if (it.isEmpty()) "" else "$it/" }
    val stamp = pickedAt.toString().padStart(14, '0')
    val rows = mutableListOf<PendingUpload>()
    for (file in files) {
        val name = file.name.replace('/', '_').trim().ifEmpty { "file" }
        val wanted = under + name
        var n = 1
        while (n < MOST_COPIES && (contains(copyName(wanted, n)) || rows.any { it.path == copyName(wanted, n) })) n++
        rows += PendingUpload(
            path = copyName(wanted, n),
            localId = file.id,
            part = AssetPart.Still,
            size = file.size,
            contentType = file.contentType,
            takenAt = stamp,
            origin = Origin.Picked,
            numbered = n,
        )
    }
    addAll(rows)
    return rows.map { it.path }
}

/**
 * A source that says how far it has been read, about every half a percent:
 * the folder draws "Uploading… 40 %" from it, and a report per chunk would be
 * a map rebuilt half a million times for a four-gigabyte video.
 */
private class Counted(
    private val inner: RawSource,
    private var at: Long,
    size: Long,
    private val report: (Long) -> Unit,
) : RawSource {
    private val step = maxOf(size / 200, 64L * 1024)
    private var told = at

    init {
        report(at)
    }

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        val read = inner.readAtMostTo(sink, byteCount)
        if (read > 0) {
            at += read
            if (at - told >= step) {
                told = at
                report(at)
            }
        }
        return read
    }

    override fun close() = inner.close()
}
