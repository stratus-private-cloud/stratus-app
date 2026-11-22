package dev.stratus.core.backup

import dev.stratus.core.server.Server
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** What a pass over the camera roll did, for whoever has to report it. */
data class BackupReport(
    val queued: Int = 0,
    val uploaded: Int = 0,
    val failed: Int = 0,
    /** Given to the system during this pass, and not finished by it. */
    val handedOver: Int = 0,
    val stopped: StoppedBecause = StoppedBecause.NothingLeft,
)

/** Why a pass ended, which is the difference between finished and interrupted. */
enum class StoppedBecause {
    NothingLeft,

    /** Everything outstanding is waiting out a backoff. Not a failure. */
    WaitingToRetry,

    /** Whoever was driving asked it to stop -- the system reclaiming the app, usually. */
    AskedTo,

    /** The library is not readable, so there is nothing to look at. */
    NoAccessToTheLibrary,

    /**
     * Parked on purpose: this connection is metered and the server says not to.
     *
     * Not a failure and not [WaitingToRetry], which would bring the system
     * back to meet the same metered connection and the same answer. What ends
     * this is wifi, which is a thing the platform can be asked to watch for
     * (stratus-app#133).
     */
    WaitingForWifi,

    /**
     * Handed to the system, which will finish it when it chooses.
     *
     * Not `WaitingToRetry`, which would be a lie about something that is not
     * waiting on us at all: on iOS a transfer outlives the pass that started
     * it, and a screen saying "waiting to retry" over a backup that is
     * actively running would be the wrong thing to tell somebody
     * (stratus-app#20).
     */
    InFlight,
}

/**
 * One pass: look at the camera roll, queue what is missing, send what is queued.
 *
 * Lives here rather than in a platform's scheduler because it is the part that
 * can be tested. What Android and iOS contribute is *when* this runs and how to
 * stay alive while it does -- neither of which is a decision about backing up.
 */
class BackupRun(
    private val source: AssetSource,
    private val prepareFor: suspend () -> ServerBackup?,
    private val journalFor: suspend () -> BackupJournal,
    private val now: () -> Long = { io.ktor.util.date.getTimeMillis() },
    /**
     * What this pass says about itself, for the one address that is narrated.
     *
     * A seam rather than a constructor call so a test can read the lines --
     * which is the only way to prove the half that matters, that every other
     * address gets none.
     */
    private val logFor: (Server) -> BackupLog = { BackupLog(it.baseUrl) },
    /** What this connection costs, for the one setting that cares. */
    private val network: Network = Network { false },
) {
    /**
     * Runs one pass for [instance].
     *
     * [keepGoing] is asked before every upload so the caller can stop at a clean
     * point: on a phone the answer becomes false when the system wants the app
     * back, and stopping between files loses nothing because the queue is in the
     * database.
     */
    suspend fun once(
        instance: Server,
        keepGoing: () -> Boolean = { true },
        onStep: (QueueStep) -> Unit = {},
    ): BackupReport {
        val journal = journalFor()
        val log = logFor(instance)
        if (source.access() == MediaAccess.None) {
            val report = BackupReport(stopped = StoppedBecause.NoAccessToTheLibrary)
            journal.ended(now(), report.stopped, 0, 0)
            log.summarise("pass: the library cannot be read")
            return report
        }
        val prepared = prepareFor() ?: return BackupReport()
        val queue = prepared.queue

        journal.began(now())
        // Stopped by the system, the pass is a cancellation and none of the
        // returns below is reached -- and a journal left saying it is running
        // is a screen saying "Working" over a phone that is not (stratus-app#230).
        var sofar = BackupReport()
        try {
            // Bounded by how far the last pass got, which is an optimisation and
            // nothing more: everything already queued is in the database and goes
            // out whatever this enumerates, and what has actually been sent is the
            // settled record's answer (stratus-app#124).
            val assets = source.assets(instance.sources, lookingBackFrom(prepared.mark.read()))

            // On a connection somebody pays for, a cold start parks before it
            // walks (stratus-app#235): asking what the server holds is a
            // listing per month the camera roll touches, megabytes for a large
            // one, and the setting is about the data and not only the uploads.
            // Nothing is queued either, since what to queue is what the walk
            // would have said.
            if (instance.onlyOnWifi && prepared.index.cold() && network.metered()) {
                val parked = BackupReport(stopped = StoppedBecause.WaitingForWifi)
                journal.ended(now(), parked.stopped, 0, 0)
                narrateEnd(log, parked, queue)
                return parked
            }

            // A phone that has settled nothing asks the server before it sends
            // anything, or a reinstall is somebody's whole camera roll going up
            // again (stratus-app#124). If the asking fails there is no point
            // guessing: uploading everything is the expensive mistake this is here
            // to avoid, so the pass waits and tries again rather than paying it.
            try {
                prepared.index.warmUp(assets)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                val report = BackupReport(stopped = StoppedBecause.WaitingToRetry)
                journal.ended(now(), report.stopped, 0, 0)
                log.summarise("pass: could not ask the server what it already has; waiting")
                return report
            }

            var report = BackupReport(queued = queue.enqueue(assets))
            // Seen against queued is the whole diagnosis: a pass that enumerates
            // forty photographs and queues none of them is working, and one that
            // queues the same forty every time is sending them again.
            if (log.on) log.say("pass began: ${assets.size} seen, ${report.queued} queued, ${queue.left()} to send")

            while (keepGoing()) {
                // After enqueuing and not before it, deliberately: looking at
                // the camera roll costs no data, and a pass that parks without
                // queueing leaves the screen saying nothing is waiting while a
                // hundred photographs are (stratus-app#133). And before every
                // file rather than once a pass: one that began on wifi and left
                // it was sending the rest of the queue over mobile data
                // (stratus-app#235).
                if (instance.onlyOnWifi && network.metered()) {
                    val parked = report.copy(stopped = StoppedBecause.WaitingForWifi)
                    advance(prepared.mark, queue, assets)
                    journal.ended(now(), parked.stopped, parked.uploaded, parked.failed)
                    narrateEnd(log, parked, queue)
                    return parked
                }
                val step = queue.runNext()
                onStep(step)
                narrate(log, step, queue)
                report = when (step) {
                    is QueueStep.Idle -> {
                        val done = report.copy(stopped = stoppedFrom(queue))
                        advance(prepared.mark, queue, assets)
                        journal.ended(now(), done.stopped, done.uploaded, done.failed)
                        narrateEnd(log, done, queue)
                        return done
                    }
                    // Nothing was learned about any photograph, and the next file
                    // would ask the same unanswering server. Ending here is one
                    // timeout for an outage instead of one per file, and
                    // `WaitingToRetry` is what brings the system back sooner than
                    // the ordinary interval (stratus-app#134).
                    is QueueStep.Unreachable -> {
                        val done = report.copy(stopped = StoppedBecause.WaitingToRetry)
                        advance(prepared.mark, queue, assets)
                        journal.ended(now(), done.stopped, done.uploaded, done.failed)
                        narrateEnd(log, done, queue)
                        return done
                    }
                    is QueueStep.Uploaded -> report.copy(uploaded = report.uploaded + 1)
                    is QueueStep.GaveUp -> report.copy(failed = report.failed + 1)
                    // A retry, a partial upload or one the system is now carrying
                    // is neither done nor lost: the row stays, and this pass simply
                    // moves on to whatever is next.
                    is QueueStep.Retrying, is QueueStep.Progressed -> report
                    is QueueStep.HandedOver -> report.copy(handedOver = report.handedOver + 1)
                }
                // Written per file so a screen reopened mid-backup says what is
                // happening rather than what was happening when it last looked.
                journal.sending((step as? QueueStep.Uploaded)?.path)
                sofar = report
            }
            val stopped = report.copy(stopped = StoppedBecause.AskedTo)
            advance(prepared.mark, queue, assets)
            journal.ended(now(), stopped.stopped, stopped.uploaded, stopped.failed)
            narrateEnd(log, stopped, queue)
            return stopped
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                journal.ended(now(), StoppedBecause.AskedTo, sofar.uploaded, sofar.failed)
            }
            throw e
        }
    }

    /**
     * One line per file, naming it and saying how much is behind it.
     *
     * The count is asked of the queue rather than carried along, because a
     * number kept beside the table is a number that eventually disagrees with
     * it -- and a log that disagrees with the screen is worse than no log. It
     * costs one query per file, paid only where the log is on.
     */
    private suspend fun narrate(log: BackupLog, step: QueueStep, queue: UploadQueue) {
        if (!log.on) return
        val left = queue.left()
        log.say(
            when (step) {
                is QueueStep.Idle -> return
                is QueueStep.Uploaded -> "sent ${step.path} -- $left left"
                is QueueStep.Progressed -> "part of ${step.path}, at ${step.offset} -- $left left"
                is QueueStep.HandedOver -> "handed ${step.path} to the system -- $left left"
                is QueueStep.Retrying -> "retrying ${step.path} in ${step.inMillis}ms (${step.detail}) -- $left left"
                is QueueStep.GaveUp -> "gave up on ${step.path} (${step.detail}) -- $left left"
                is QueueStep.Unreachable -> "the server is not answering (${step.detail}) -- $left left"
            },
        )
    }

    /** The line the rest of the pass hangs from, and on Android the event. */
    private suspend fun narrateEnd(log: BackupLog, report: BackupReport, queue: UploadQueue) {
        if (!log.on) return
        log.summarise(
            "pass ended ${report.stopped}: ${report.uploaded} sent, " +
                "${report.failed} failed, ${queue.left()} left",
        )
    }

    /**
     * Moves the bound to the oldest thing still owed, or past everything seen
     * when nothing is.
     *
     * Two rules, both of which are about never skipping a photograph. It stops
     * at what is still owed rather than at the newest success, so a file that
     * keeps failing holds the bound behind it instead of being enumerated away.
     * And **one asset with no added date freezes it entirely**: that is every
     * iOS below 26, where the platform has no such thing to report and a bound
     * taken from a guess would be a photograph nobody backs up.
     *
     * What is already queued is unaffected either way. The queue is the
     * database, so a row below the bound is still sent.
     */
    /**
     * The bound a pass asks for, a day behind the mark (stratus-app#231).
     *
     * The mark is the newest arrival a pass saw, and something can become
     * visible after it with an older one: a video is inserted when recording
     * starts and hidden until it stops, and a pass another photograph started
     * meanwhile moved the mark past it for good. Looking back a day costs a
     * listing of a day of photographs the settled record already answers.
     */
    private fun lookingBackFrom(mark: Long): Long = if (mark <= 0) 0 else maxOf(1, mark - LOOK_BACK_MS)

    private suspend fun advance(mark: BackupMark, queue: UploadQueue, assets: List<Asset>) {
        if (assets.isEmpty() || assets.any { it.addedAtEpochMs <= 0 }) return
        val owed = queue.outstanding().mapTo(mutableSetOf()) { it.localId }
        val oldestOwed = assets.filter { it.localId in owed }.minOfOrNull { it.addedAtEpochMs }
        mark.advanceTo(oldestOwed?.minus(1) ?: assets.maxOf { it.addedAtEpochMs })
    }

    /**
     * Idle means nothing is *runnable*, which is not the same as nothing being
     * left: work waiting out a backoff is still work, and a screen that said
     * "finished" over it would be lying.
     */
    private suspend fun stoppedFrom(queue: UploadQueue): StoppedBecause {
        val left = queue.summary()
        return when {
            left.total == 0 -> StoppedBecause.NothingLeft
            // Asked in this order because a pass can end with both, and what
            // somebody wants to be told is that something is moving.
            left.inFlight > 0 -> StoppedBecause.InFlight
            else -> StoppedBecause.WaitingToRetry
        }
    }

    companion object {
        const val LOOK_BACK_MS = 24 * 60 * 60 * 1_000L
    }
}
