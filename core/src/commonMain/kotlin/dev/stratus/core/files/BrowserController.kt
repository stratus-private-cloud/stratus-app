package dev.stratus.core.files

import dev.stratus.core.dav.DavClient
import dev.stratus.core.dav.DavError
import dev.stratus.core.dav.DavResource
import dev.stratus.core.share.ShareLife
import dev.stratus.core.share.LinkAnswer
import dev.stratus.core.share.Sharing
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.exhausted
import io.ktor.utils.io.readRemaining
import kotlinx.io.Sink
import kotlinx.io.readByteArray

/**
 * Walking the tree, and the four things that can be done to what is in it.
 *
 * Every verb here is standard WebDAV, so this works against any server: browse
 * is PROPFIND, download is GET, rename is MOVE and delete is DELETE. The limits
 * come with them, and the one that shows is renaming a folder with anything in
 * it -- refused by Stratus today, and said so in those words rather than as a
 * generic failure that reads like a broken app.
 */
class BrowserController(
    private val dav: DavClient,
    private val handoff: FileHandoff,
    private val scope: CoroutineScope,
    private val sharing: Sharing? = null,
    /**
     * The folder this browser cannot climb out of: `/` for the whole server,
     * or a section's collection on a Stratus, such as `/music/`.
     */
    val floor: String = "/",
    /**
     * A Stratus's `photos/` or `videos/`, drawn as one scroll of months
     * rather than walked a year and a month at a time -- the web's gallery.
     * It is still the same collections listed with `PROPFIND`, a month per
     * request as the scroll reaches it, because the server refuses
     * `Depth: infinity`.
     */
    val timeline: Boolean = false,
) {
    /** Whether there is anybody to share through, which the row menu asks. */
    val canShare: Boolean get() = sharing?.offered == true

    private val mutable = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = mutable.asStateFlow()

    private var running: Job? = null

    /**
     * Lists where the browser is, except that a Stratus rooted at its origin
     * opens on `files/`: the origin there is a list of generated collections,
     * and the tree somebody came for is one level down.
     *
     * Asked once, and a server that cannot be asked is not a Stratus: the
     * listing that follows is what reports it unreachable.
     */
    fun start() {
        running?.cancel()
        mutable.value = mutable.value.copy(busy = true, failure = null, pending = null)
        running = scope.launch {
            var path = mutable.value.path
            if (path == "/" && floor != "/") {
                path = floor
            } else if (path == "/" && dav.isStratusAtOrigin()) {
                path = STRATUS_HOME
            }
            mutable.value = mutable.value.copy(path = path)
            if (timeline) openTimeline(1) else load(path)
        }
    }


    fun enter(directory: DavResource) = go(directory.path)

    /**
     * True when there was somewhere to go. The floor is the end of the line,
     * and a photograph open or a selection made is a step of its own: back
     * undoes it first.
     */
    fun goUp(): Boolean {
        if (stopViewing()) return true
        if (clearSelection()) return true
        val here = mutable.value.path.trimEnd('/')
        if (here.isEmpty() || here == floor.trimEnd('/')) return false
        go(here.substringBeforeLast('/').ifEmpty { "" } + "/")
        return true
    }

    fun refresh() = go(mutable.value.path)

    private fun go(path: String) {
        running?.cancel()
        mutable.value = mutable.value.copy(
            path = path, busy = true, failure = null, pending = null, viewing = null, selected = emptySet(),
        )
        running = scope.launch { reload() }
    }

    /** What is on screen asked for again: the folder, or as many months as were loaded. */
    private suspend fun reload() {
        if (timeline) openTimeline(mutable.value.months.size) else load(mutable.value.path)
    }

    /** The years not opened yet and the months of the open one not listed yet, newest first. */
    private val yearsLeft = ArrayDeque<String>()
    private val monthsLeft = ArrayDeque<String>()

    /**
     * Starts the timeline again from the newest month, and publishes it only
     * once [months] of them are in: a refresh is the same scroll with whatever
     * is new on top, not an empty screen that fills up again.
     */
    private suspend fun openTimeline(months: Int) {
        try {
            yearsLeft.clear()
            monthsLeft.clear()
            yearsLeft.addAll(newestFirst(dav.list(floor)))
            val loaded = mutableListOf<TimelineMonth>()
            while (loaded.size < maxOf(months, 1)) loaded += nextMonth() ?: break
            mutable.value = mutable.value.copy(
                months = loaded, entries = loaded.flatMap { it.entries }, more = moreLeft(), busy = false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: DavError) {
            mutable.value = mutable.value.copy(
                busy = false, months = emptyList(), entries = emptyList(), more = false, failure = BrowserFailure.Listing(e),
            )
        } catch (e: Exception) {
            mutable.value = mutable.value.copy(
                busy = false, months = emptyList(), entries = emptyList(), more = false, failure = unreachable(e),
            )
        }
    }

    /**
     * The next older month of the timeline, where the scroll has reached the
     * end of what is loaded. Does nothing while anything else is running --
     * the scroll asks again once that is done.
     */
    fun loadMore() {
        val now = mutable.value
        if (!timeline || !now.more || now.busy || running?.isActive == true) return
        mutable.value = now.copy(busy = true, failure = null)
        running = scope.launch {
            try {
                val month = nextMonth()
                val state = mutable.value
                mutable.value = state.copy(
                    months = if (month == null) state.months else state.months + month,
                    entries = if (month == null) state.entries else state.entries + month.entries,
                    more = moreLeft(),
                    busy = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: DavError) {
                // What is loaded stays: it is still true, and the month that
                // failed is still next for when somebody tries again.
                mutable.value = mutable.value.copy(busy = false, failure = BrowserFailure.Listing(e))
            } catch (e: Exception) {
                mutable.value = mutable.value.copy(busy = false, failure = unreachable(e))
            }
        }
    }

    /**
     * Lists the next month with anything in it, opening years as they run
     * out, or null at the oldest. A collection is taken off the queue only
     * once it has been listed, so a failure leaves it next.
     */
    private suspend fun nextMonth(): TimelineMonth? {
        while (true) {
            val month = monthsLeft.firstOrNull()
            if (month == null) {
                val year = yearsLeft.firstOrNull() ?: return null
                monthsLeft.addAll(newestFirst(dav.list(year)))
                yearsLeft.removeFirst()
                continue
            }
            // By name, newest first: the date a listing gives is when the file
            // was written rather than taken, and the names a backup gives
            // start with the moment of the photograph.
            val files = dav.list(month).filter { !it.isDirectory }.sortedByDescending { it.name }
            monthsLeft.removeFirst()
            if (files.isNotEmpty()) return TimelineMonth(month, files)
        }
    }

    private fun moreLeft() = yearsLeft.isNotEmpty() || monthsLeft.isNotEmpty()

    /** A year's or a month's folders, which are numbers, newest first. */
    private fun newestFirst(listing: List<DavResource>) =
        listing.filter { it.isDirectory }.sortedByDescending { it.name.toIntOrNull() ?: -1 }.map { it.path }

    private suspend fun load(path: String) {
        try {
            mutable.value = mutable.value.copy(entries = dav.list(path).sortedForPeople(), busy = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DavError) {
            mutable.value = mutable.value.copy(busy = false, entries = emptyList(), failure = BrowserFailure.Listing(e))
        } catch (e: Exception) {
            // Cleared like any failed listing: the path has already moved,
            // and the previous folder's rows under this one's name would be
            // a listing of somewhere else.
            mutable.value = mutable.value.copy(busy = false, entries = emptyList(), failure = unreachable(e))
        }
    }

    fun openFile(entry: DavResource) = transfer(entry) { name, type, body -> handoff.open(name, type, body) }

    fun saveFile(entry: DavResource) = transfer(entry) { name, type, body -> handoff.save(name, type, body) }

    fun sendFile(entry: DavResource) = transfer(entry) { name, type, body -> handoff.send(name, type, body) }

    /**
     * Opens [entry] in the app's own viewer, with the other pictures in
     * [among] either side of it -- the folder's rows, or a search's.
     */
    fun view(entry: DavResource, among: List<DavResource> = mutable.value.entries) {
        val pictures = among.filter(::isPicture)
        val at = pictures.indexOfFirst { it.path == entry.path }
        if (at < 0) return
        mutable.value = mutable.value.copy(viewing = Viewing(pictures, at), pending = null)
    }

    /** True when there was a photograph open. */
    fun stopViewing(): Boolean {
        if (mutable.value.viewing == null) return false
        mutable.value = mutable.value.copy(viewing = null)
        return true
    }

    /**
     * The file itself, not the server's rendering of it: a Stratus would make
     * a 1200-pixel JPEG, and any other server makes nothing. Outside [running]
     * on purpose -- the viewer asks for the photographs either side while one
     * is shown, and none of them is a reason to cancel a listing.
     */
    suspend fun original(entry: DavResource): Original {
        if ((entry.size ?: 0) > LARGEST_ORIGINAL) return Original.TooLarge
        return try {
            dav.read(entry.path) { channel ->
                // One byte over the ceiling is how a server that reported no
                // size, or the wrong one, is caught without reading the rest.
                val bytes = channel.readRemaining(LARGEST_ORIGINAL + 1).readByteArray()
                if (bytes.size > LARGEST_ORIGINAL) Original.TooLarge else Original.Bytes(bytes)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Original.Failed
        }
    }

    private fun transfer(
        entry: DavResource,
        hand: suspend (String, String?, suspend (Sink) -> Unit) -> Unit,
    ) {
        running?.cancel()
        mutable.value = mutable.value.copy(busy = true, failure = null)
        running = scope.launch {
            try {
                hand(entry.name, entry.contentType) { sink ->
                    dav.read(entry.path) { channel -> channel.writeTo(sink) }
                }
                mutable.value = mutable.value.copy(busy = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(busy = false, failure = BrowserFailure.Transfer(entry.name))
            }
        }
    }

    fun ask(confirmation: Confirmation) {
        mutable.value = mutable.value.copy(pending = confirmation, failure = null)
    }

    fun dismiss() {
        mutable.value = mutable.value.copy(pending = null)
    }

    /** Deletes what [ask] last put up. Does nothing if nothing was asked about. */
    fun confirmDelete() {
        val target = (mutable.value.pending as? Confirmation.Delete)?.target ?: return
        act(target) { dav.delete(target.path) }
    }

    /**
     * Mints a link for what [ask] last put up and hands it to the system.
     *
     * Nothing is sent to the server: the signature comes from the password this
     * app already holds, so making a link is arithmetic and the first time the
     * server hears about it is when somebody opens it.
     */
    fun confirmShare(life: ShareLife) {
        val target = (mutable.value.pending as? Confirmation.Share)?.target ?: return
        val sharing = sharing ?: return
        mutable.value = mutable.value.copy(pending = null)
        scope.launch {
            // Whether links work is asked of the server, anonymously, so a
            // server that is not there is an answer here too.
            val failure = when (sharing.offer(target, life)) {
                LinkAnswer.Honoured -> return@launch
                LinkAnswer.Refused -> BrowserFailure.TheServerDoesNotDoLinks
                LinkAnswer.NoAnswer -> BrowserFailure.Unreachable(timedOut = false)
            }
            mutable.value = mutable.value.copy(failure = failure)
        }
    }

    /** Ticks [entry] if it was not, and unticks it if it was. */
    fun toggle(entry: DavResource) {
        val selected = mutable.value.selected
        mutable.value = mutable.value.copy(
            selected = if (entry.path in selected) selected - entry.path else selected + entry.path,
        )
    }

    /** True when something was selected. */
    fun clearSelection(): Boolean {
        if (mutable.value.selected.isEmpty()) return false
        mutable.value = mutable.value.copy(selected = emptySet())
        return true
    }

    fun askToDeleteSelected() {
        val state = mutable.value
        val targets = state.entries.filter { it.path in state.selected }
        if (targets.isNotEmpty()) ask(Confirmation.DeleteSeveral(targets))
    }

    /**
     * One `DELETE` per file, in the order they are listed, which is what
     * WebDAV has and what makes each a line of its own in a Stratus's trash.
     * One the server refuses does not stop the rest; one it never answers
     * does, since the next would ask the same silent server.
     */
    fun confirmDeleteSeveral() {
        val targets = (mutable.value.pending as? Confirmation.DeleteSeveral)?.targets ?: return
        running?.cancel()
        mutable.value = mutable.value.copy(busy = true, pending = null, failure = null)
        running = scope.launch {
            var failure: BrowserFailure? = null
            for (target in targets) {
                try {
                    dav.delete(target.path)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DavError) {
                    failure = failure ?: classify(e, target)
                } catch (e: Exception) {
                    failure = unreachable(e)
                    break
                }
            }
            mutable.value = mutable.value.copy(selected = emptySet())
            reload()
            if (failure != null) mutable.value = mutable.value.copy(failure = failure)
        }
    }

    fun confirmRename(to: String) {
        val target = (mutable.value.pending as? Confirmation.Rename)?.target ?: return
        val name = to.trim()
        if (name.isEmpty() || '/' in name) return
        // A rename is a rename: reducing it to one path element is what stops a
        // typed path quietly carrying the file across the tree.
        val destination = target.path.trimEnd('/').substringBeforeLast('/') + "/" + name
        act(target) { dav.move(target.path, destination) }
    }

    private fun act(target: DavResource, work: suspend () -> Unit) {
        running?.cancel()
        mutable.value = mutable.value.copy(busy = true, pending = null, failure = null)
        running = scope.launch {
            try {
                work()
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: DavError) {
                mutable.value = mutable.value.copy(busy = false, failure = classify(e, target))
            } catch (e: Exception) {
                mutable.value = mutable.value.copy(busy = false, failure = unreachable(e))
            }
        }
    }

    private fun unreachable(e: Exception) = BrowserFailure.Unreachable(
        timedOut = e is HttpRequestTimeoutException || e is SocketTimeoutException,
    )

    /**
     * A 409 on a directory is the server saying it cannot move one that still has
     * contents. It says the same 409 when a destination's parent is missing, and
     * cannot tell them apart for us -- but we know what we asked for.
     */
    private fun classify(error: DavError, target: DavResource): BrowserFailure =
        if (error is DavError.Conflict && target.isDirectory) {
            BrowserFailure.RenameNeedsAnEmptyFolder(target.name)
        } else {
            BrowserFailure.Operation(error)
        }

    /**
     * Moves the bytes across a chunk at a time.
     *
     * Never `readRemaining()` with no bound, which would put the whole file in
     * memory -- the files this app exists for are videos.
     */
    private suspend fun ByteReadChannel.writeTo(sink: Sink) {
        while (!exhausted()) {
            sink.transferFrom(readRemaining(CHUNK_BYTES))
        }
        sink.flush()
    }

    private companion object {
        const val CHUNK_BYTES = 64L * 1024
    }
}

internal const val STRATUS_HOME = "/files/"

/**
 * Whether this is a Stratus and the app is pointed at its origin, which is
 * when its collections can be told apart into sections. Pointed anywhere
 * below that, somebody chose where to be, and it is respected.
 *
 * A server that cannot be asked is not a Stratus: whatever is shown next is
 * what reports it unreachable.
 */
suspend fun DavClient.isStratusAtOrigin(): Boolean = basePath.isEmpty() && try {
    stratusVersion() != null
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    false
}

/**
 * Folders first and then by name, which is how a listing is read.
 *
 * Shared with the search results rather than written twice: two lists of the
 * same rows ordered two ways is a difference somebody has to notice and
 * explain, and there is no reason for one.
 */
internal fun List<DavResource>.sortedForPeople(): List<DavResource> =
    sortedWith(compareByDescending<DavResource> { it.isDirectory }.thenBy { it.name.lowercase() })
