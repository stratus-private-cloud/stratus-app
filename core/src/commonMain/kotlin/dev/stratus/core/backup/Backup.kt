package dev.stratus.core.backup

import dev.stratus.core.dav.DavClient
import dev.stratus.core.server.Server
import dev.stratus.core.server.ServerStore
import dev.stratus.core.dav.DavError
import io.ktor.client.HttpClient
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** An instance, reached: both clients, because tus is negotiated off the raw one. */
class Connection(val http: HttpClient, val dav: DavClient)

/**
 * How an instance is reached.
 *
 * An interface for one reason: it is the only part of assembling a backup that
 * needs an HTTP engine and a keychain, so putting it behind this is what lets
 * everything else here be built in a test. The composition root implements it;
 * `commonTest` implements it with a mock engine and no keychain at all.
 */
fun interface Connections {
    suspend fun to(instance: Server): Connection?
}

/** One instance's half of a pass: what to send, what the server has, how far to look. */
class ServerBackup(val queue: UploadQueue, val index: BackupIndex, val mark: BackupMark)

/** Whether the system should bring us back, decided here rather than per platform. */
enum class PassOutcome {
    Finished,
    ComeBackLater,

    /**
     * Parked until the connection stops costing money (stratus-app#133).
     *
     * Its own answer and not [ComeBackLater], because coming back later means
     * coming back to the same metered connection: what this asks for is to be
     * woken by wifi, which both platforms can watch for and neither does by
     * retrying.
     */
    WhenThereIsWifi,
}

/**
 * Everything about backing a camera roll up, assembled.
 *
 * Split out of `AppContainer` because that class cannot be tested -- it opens
 * SQLite, builds HTTP clients and reads the keychain -- so every decision that
 * drifted into it left the fast loop. What is here is the same code with a seam
 * in front of the part that needs a device, and tests around the rest.
 */
class Backup(
    private val instances: ServerStore,
    private val database: BackupDatabase,
    private val assets: AssetSource,
    private val connections: Connections,
    /**
     * How bytes leave this device, which is the one thing a platform may
     * answer differently.
     *
     * Android negotiates between the two Ktor transports and is done. iOS
     * hands the body to the system instead, because a transfer there has to
     * outlive the app (stratus-app#20) -- so the composition root passes one
     * that does, and nothing else here changes.
     */
    private val transports: suspend (Connection, Server) -> Transport =
        { connection, instance -> transportFor(connection, instance.baseUrl) },
    /**
     * What the platform says it is still carrying, or null where nothing is.
     *
     * Asked at the start of a pass. Null and empty are not the same: empty
     * means "nothing is in flight, release everything", which is right after
     * a reinstall and wrong on a platform that has no such notion at all.
     */
    private val liveTransfers: (suspend () -> Set<String>)? = null,
    /** What the connection costs, for the server that asked not to pay it. */
    private val network: Network = Network { false },
    /** How much may be in the system's hands at once, where transfers are handed over (stratus-app#233). */
    private val handOverAtMost: HandOverBudget? = null,
    /** Where files picked by hand are read from (stratus-app#127), or null where nothing can pick one. */
    private val picked: PickedFiles? = null,
    private val now: () -> Long = { getTimeMillis() },
) {
    private val sent = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** How far each hand upload being sent in this process has got, by path. */
    val progress: StateFlow<Map<String, Long>> = sent.asStateFlow()

    /** What to say about the backup, assembled from what the queue actually holds. */
    val status: BackupStatus by lazy {
        BackupStatus(database, assets, parked = ::parked, passing = { guard.withLock { passing } })
    }

    /** The server a pass is owed to, if there is one and it has sources. */
    suspend fun enabled(): Server? = instances.instance()?.takeIf { it.backupEnabled }

    /**
     * One pass over the camera roll.
     *
     * It lives here rather than in each platform's scheduler, which is where
     * it was: whether a pass happens at all and whether to ask to be woken
     * again are decisions, and a decision in a `Worker` is a decision no test
     * can reach. The platform is left holding a notification and a return
     * value.
     */
    suspend fun pass(
        keepGoing: () -> Boolean = { true },
        onStep: (QueueStep) -> Unit = {},
    ): PassOutcome {
        // One at a time, whoever asks (stratus-app#229). Android runs its four
        // unique works side by side and iOS a tap beside the system's task, and
        // two passes take the same head of the queue and send it twice. The one
        // that arrives second is not dropped, though: the pass running looks at
        // the camera roll again when it is done, which is what a tap for the
        // photograph just taken was asking for (stratus-app#188).
        val first = guard.withLock {
            if (passing) {
                again = true
                false
            } else {
                passing = true
                true
            }
        }
        if (!first) return PassOutcome.Finished

        var released = false
        try {
            while (true) {
                val outcome = onePass(keepGoing, onStep)
                val more = guard.withLock {
                    val more = again && outcome == PassOutcome.Finished && keepGoing()
                    again = false
                    if (!more) passing = false
                    more
                }
                if (!more) {
                    released = true
                    return outcome
                }
            }
        } finally {
            if (!released) withContext(NonCancellable) { guard.withLock { passing = false; again = false } }
        }
    }

    private val guard = Mutex()
    private var passing = false
    private var again = false

    private suspend fun onePass(keepGoing: () -> Boolean, onStep: (QueueStep) -> Unit): PassOutcome {
        liveTransfers?.let { live -> reconcile(live()) }
        if (!keepGoing()) return PassOutcome.Finished
        // One queue for both halves of the pass, so the transport is
        // negotiated once whichever of them needs it first.
        var prepared: ServerBackup? = null
        val prepareOnce: suspend () -> ServerBackup? = { prepared ?: prepare().also { prepared = it } }

        val byHand = byHand(prepareOnce, keepGoing, onStep)
        val instance = enabled() ?: return byHand
        if (!keepGoing()) return byHand
        val report = run(prepareOnce).once(instance, keepGoing, onStep)
        // InFlight too, and not because the system needs reminding about what
        // it is carrying -- it does not. A pass that ended with something in
        // flight may also have left rows serving out a backoff, and that
        // reason is the one that got reported.
        val backup = when (report.stopped) {
            StoppedBecause.WaitingForWifi -> PassOutcome.WhenThereIsWifi
            StoppedBecause.WaitingToRetry, StoppedBecause.InFlight -> PassOutcome.ComeBackLater
            else -> PassOutcome.Finished
        }
        return when {
            byHand == PassOutcome.ComeBackLater || backup == PassOutcome.ComeBackLater -> PassOutcome.ComeBackLater
            else -> backup
        }
    }

    /**
     * Sends what was picked by hand, whether or not there is a backup at all
     * (stratus-app#127): somebody who never chose a folder to back up can
     * still put a document on their server, and the pass is the one place
     * that holds the lock, the transports and the budget.
     *
     * Before the camera roll and apart from it, so it needs no access to the
     * library and never enumerates it. And not parked for wifi: that setting
     * is the backup's, and a file somebody has just pressed upload on is a
     * thing they asked to go now.
     */
    private suspend fun byHand(
        prepare: suspend () -> ServerBackup?,
        keepGoing: () -> Boolean,
        onStep: (QueueStep) -> Unit,
    ): PassOutcome {
        if (handOwed() == 0) return PassOutcome.Finished
        val queue = prepare()?.queue ?: return PassOutcome.Finished
        while (keepGoing()) {
            if (handOverAtMost != null && handOverAtMost.spent(queue.summary())) return PassOutcome.ComeBackLater
            val step = queue.runNext(Origin.Picked)
            onStep(step)
            when (step) {
                is QueueStep.Idle -> break
                is QueueStep.Unreachable -> return PassOutcome.ComeBackLater
                else -> {}
            }
        }
        return if (handOwed() == 0) PassOutcome.Finished else PassOutcome.ComeBackLater
    }

    /** Hand uploads still to send; one given up on is waiting for somebody, not for a pass. */
    private suspend fun handOwed(): Int =
        database.pending().summary(now(), Origin.Picked).let { it.total - it.givenUp }

    /**
     * A pass, assembled.
     *
     * Built here rather than by each platform's scheduler, so that what a pass
     * is made of is decided once: the platforms contribute only when it runs.
     */
    suspend fun run(prepareFor: suspend () -> ServerBackup? = { prepare() }): BackupRun = BackupRun(
        source = assets,
        prepareFor = prepareFor,
        journalFor = { database.journal() },
        network = network,
        handOverAtMost = handOverAtMost,
    )

    /**
     * Whether the backup is parked for wifi right now.
     *
     * The same two facts the pass weighs, asked again for the screen rather
     * than remembered from the last pass: the connection changes between
     * passes, and a screen saying "waiting for wifi" over a phone that is on
     * wifi is the kind of lie this surface exists to avoid.
     */
    suspend fun parked(): Boolean {
        val instance = enabled() ?: return false
        return instance.onlyOnWifi && network.metered()
    }

    /**
     * Everything a pass needs, assembled from the server's own settings.
     *
     * The index comes out of the same connection rather than a second one,
     * because building one is an HTTP client and an engine.
     */
    suspend fun prepare(): ServerBackup? {
        val instance = instances.instance() ?: return null
        val connection = connections.to(instance) ?: return null
        val layout = RemoteLayout(instance.backupRoot)
        val cache = database.cache()
        return ServerBackup(
            queue = UploadQueue(
                layout = layout,
                pending = database.pending(),
                cache = cache,
                source = assets,
                transport = transports(connection, instance),
                directories = DavDirectoryMaker(connection.dav),
                now = now,
                picked = picked,
                taken = { path -> isTaken(connection.dav, path) },
                progress = sent,
            ),
            index = BackupIndex(layout, cache, connection.dav),
            mark = database.mark(),
        )
    }

    /**
     * The answer to a transfer the system was carrying, whoever it belongs to.
     *
     * **Built from the database alone**, with no connection: the upload has
     * already happened, and refusing to write it down because a client could
     * not be constructed -- the phone being in a tunnel, say -- would throw
     * away the only record that it did.
     */
    suspend fun settle(ticket: String, answer: TransferAnswer): QueueStep =
        records().settle(ticket, answer)

    /** Puts back everything the platform has forgotten it was carrying. */
    suspend fun reconcile(live: Set<String>): Int = records().reconcile(live)

    private suspend fun records() = UploadRecords(database.pending(), database.cache(), now, picked)

    /** What has gone wrong with the backup, with what the server said about each, bounded. */
    suspend fun failures(limit: Int = 20): List<PendingUpload> =
        database.pending().failures(limit, Origin.Library)

    /** Whether a pass has anything to do: a backup to run, or a file picked by hand to send. */
    suspend fun owed(): Boolean =
        enabled() != null || handOwed() > 0

    /**
     * Queues [files] into [folder], to be sent by the next pass -- which the
     * caller asks for, since only the platform can start one that outlives
     * the screen (stratus-app#127).
     */
    suspend fun uploadByHand(folder: String, files: List<PickedFile>): List<String> =
        database.pending().enqueueByHand(folder, files, now())

    /** Every hand upload not yet finished, given up on or not. */
    suspend fun handUploads(): List<PendingUpload> = database.pending().all(Origin.Picked)

    /**
     * Takes a hand upload off the queue. One the system is carrying finishes
     * anyway, and is recorded against a row that is no longer there -- which
     * is nothing, as an unknown ticket always is.
     */
    suspend fun cancelHandUpload(path: String) {
        val pending = database.pending()
        val row = pending.all(Origin.Picked).firstOrNull { it.path == path } ?: return
        pending.remove(path)
        picked?.forget(row.localId)
    }

    /** Lets go of every file picked by hand, for signing out, which empties the queue. */
    suspend fun forgetHandUploads() {
        for (row in database.pending().all(Origin.Picked)) picked?.forget(row.localId)
    }

    /**
     * Which sources feed the backup, which is also the switch.
     *
     * **Empty means no backup at all.** There is no separate flag beside it:
     * two ways to say "not now" is one of them eventually disagreeing with the
     * other, and the question somebody is actually answering on that screen is
     * which photographs they want kept.
     */
    suspend fun setSources(sources: Set<String>) {
        val instance = instances.instance() ?: return
        instances.update(instance.copy(sources = sources))
    }

    /** Whether a pass may spend somebody's data plan (stratus-app#133). */
    suspend fun setOnlyOnWifi(only: Boolean) {
        val instance = instances.instance() ?: return
        instances.update(instance.copy(onlyOnWifi = only))
    }

    /** What there is to choose from on this platform, with how much is in each. */
    suspend fun sources() = assets.sources()

    /** Whether the photo library can be read at all, which decides what to say. */
    suspend fun access() = assets.access()
}

/** Whether [path] holds anything, by one `Depth: 0` `PROPFIND`; anything but a 404 is an answer to report. */
internal suspend fun isTaken(dav: DavClient, path: String): Boolean = try {
    dav.stat(path)
    true
} catch (e: CancellationException) {
    throw e
} catch (_: DavError.NotFound) {
    false
}
