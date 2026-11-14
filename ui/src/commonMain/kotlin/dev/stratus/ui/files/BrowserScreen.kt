package dev.stratus.ui.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stratus.ui.backup.size
import dev.stratus.ui.dateTime
import dev.stratus.core.cast.CastController
import dev.stratus.core.cast.CastDevice
import dev.stratus.core.cast.CastState
import dev.stratus.core.dav.DavError
import dev.stratus.core.dav.DavResource
import dev.stratus.core.files.BrowserController
import dev.stratus.core.files.BrowserFailure
import dev.stratus.core.files.Confirmation
import dev.stratus.core.files.TimelineMonth
import dev.stratus.core.files.isPicture
import dev.stratus.core.play.MusicController
import dev.stratus.core.play.VideoController
import dev.stratus.core.share.ShareLife
import dev.stratus.ui.Glyph
import dev.stratus.ui.Symbol
import dev.stratus.ui.StratusMark
import dev.stratus.ui.decodePicture

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    controller: BrowserController,
    cast: CastController?,
    onOpenSettings: () -> Unit,
    /** Null where the server cannot search, which is most WebDAV servers. */
    onSearch: (() -> Unit)? = null,
    /** For a server that stopped accepting the sign-in this app holds. */
    onEditServer: () -> Unit = {},
    /** Drawn under the bar and above everything else: the backup strip, from App. */
    banner: @Composable () -> Unit = {},
    /** The sections of a Stratus, from App; nothing against any other server. */
    bottomBar: @Composable () -> Unit = {},
    /**
     * A folder's picture where there is one -- an album's cover -- and, when
     * this answers for the folder somebody is in, its rows are drawn as tiles.
     */
    cover: (suspend (DavResource) -> ImageBitmap?)? = null,
    /** Whether the folder at this path is shown as tiles rather than rows. */
    tiles: (String) -> Boolean = { false },
    /** A way back out of a browser opened over another screen, such as the trash. */
    onClose: (() -> Unit)? = null,
    /** Playing a track here, or null where this platform has no player. */
    music: MusicController? = null,
    /** Playing a film here, or null where this platform cannot. */
    videos: VideoController? = null,
    /** Whether tapping a track or a film plays it, which is what Music and Videos are for. */
    playOnTap: Boolean = false,
) {
    val state by controller.state.collectAsState()
    // Which of two indicators a listing shows: the pulled one for a pull, the
    // bar for everything else, and never both.
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(state.busy) { if (!state.busy) pulled = false }
    val selecting = state.selected.isNotEmpty()

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            // The app's own bar, and the only one that is: the mark and the name on
            // the left and the menu on the right, which is the web UI's navbar
            // (stratus-app#140). What used to be here -- the path, and an arrow to
            // climb out of it -- is a row of its own below, because a bar that
            // changes with the folder is not somewhere to put the thing that never
            // changes.
            topBar = {
                TopAppBar(
                    // Ticking rows turns the bar into the selection's own, the
                    // way the web's turns its toolbar into a delete button.
                    navigationIcon = {
                        if (selecting) {
                            IconButton(
                                onClick = { controller.clearSelection() },
                                modifier = Modifier.semantics { contentDescription = "Clear the selection" },
                            ) { Symbol(Glyph.CLOSE, Modifier.clearAndSetSemantics {}) }
                        } else if (onClose != null) {
                            IconButton(onClick = onClose) { Symbol(Glyph.BACK) }
                        }
                    },
                    title = {
                        if (selecting) {
                            Text("${state.selected.size} selected")
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(StratusMark, contentDescription = null, Modifier.size(22.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Stratus")
                            }
                        }
                    },
                    actions = {
                        if (selecting) {
                            IconButton(
                                onClick = controller::askToDeleteSelected,
                                modifier = Modifier.semantics { contentDescription = "Delete the selected" },
                            ) { Symbol(Glyph.DELETE, Modifier.clearAndSetSemantics {}) }
                        } else {
                            if (onSearch != null) {
                                IconButton(onClick = onSearch) { Symbol(Glyph.SEARCH) }
                            }
                            IconButton(onClick = onOpenSettings) { Symbol(Glyph.SETTINGS) }
                        }
                    },
                )
            },
            bottomBar = bottomBar,
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                banner()
                // A timeline has no folder to say and nowhere to climb to, and
                // the next month loading shows at the end of the scroll.
                if (!controller.timeline) Where(state.path, controller.floor, up = { controller.goUp() })
                if (state.busy && !pulled && !(controller.timeline && state.months.isNotEmpty())) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                state.failure?.let {
                    Text(explain(it), Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp))
                    // Without it, the only way to ask again after a server came back
                    // was to walk somewhere else and back.
                    if (it.isRejectedSignIn()) {
                        // Trying again with the same password cannot help, and the
                        // next thing somebody needs is the form to type the new one.
                        TextButton(onClick = onEditServer, Modifier.padding(horizontal = 8.dp)) { Text("Edit server") }
                    } else {
                        TextButton(onClick = controller::refresh, Modifier.padding(horizontal = 8.dp)) { Text("Try again") }
                    }
                }

                if (cast != null) CastBar(cast)

                // The one way to see what another client added since the folder was
                // opened, short of walking out and back in (stratus-app#82).
                PullToRefreshBox(
                    isRefreshing = pulled && state.busy,
                    onRefresh = {
                        pulled = true
                        controller.refresh()
                    },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (controller.timeline && cover != null) {
                        Timeline(state.months, state.more, controller, cover, videos, state.selected)
                    } else if (cover != null && tiles(state.path)) {
                        LazyVerticalGrid(
                            GridCells.Adaptive(150.dp),
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(state.entries, key = { it.path }) { entry ->
                                Tile(
                                    entry, controller, cover,
                                    onPlay = videos?.takeIf { it.canPlay(entry) }?.let { v -> { v.play(entry) } },
                                    selected = entry.path in state.selected,
                                    selecting = selecting,
                                )
                            }
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(state.entries, key = { it.path }) { entry ->
                                Entry(
                                    entry, controller, cast,
                                    onPlay = music?.takeIf { it.canPlay(entry) }?.let { m -> { m.play(state.entries, entry) } }
                                        ?: videos?.takeIf { it.canPlay(entry) }?.let { v -> { v.play(entry) } },
                                    playOnTap = playOnTap,
                                    selected = entry.path in state.selected,
                                    selecting = selecting,
                                    onSelect = controller::toggle,
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }

        state.viewing?.let { PhotoViewer(it, controller) }
    }

    when (val pending = state.pending) {
        is Confirmation.Delete -> DeleteDialog(pending.target, controller)
        is Confirmation.DeleteSeveral -> DeleteSeveralDialog(pending.targets, controller)
        is Confirmation.Rename -> RenameDialog(pending.target, controller)
        is Confirmation.Share -> ShareDialog(pending.target, controller)
        is Confirmation.Details -> DetailsDialog(pending.target, controller)
        null -> {}
    }
}

/**
 * Which folder this is, and the way out of it.
 *
 * Under the bar rather than in it, and the arrow with it: they belong to the
 * listing, and the listing is what changes. At the origin there is nowhere to
 * climb to, so there is no arrow -- the collections there are the top.
 */
@Composable
private fun Where(path: String, floor: String, up: () -> Unit) {
    val top = path.trimEnd('/') == floor.trimEnd('/')
    Row(
        Modifier.fillMaxWidth().padding(start = if (top) 16.dp else 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!top) {
            IconButton(onClick = up) {
                Symbol(Glyph.BACK)
            }
        }
        Text(
            path,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A Stratus's photographs or recordings as one scroll, newest first, under a
 * heading per month -- the web's gallery. The next month is asked for as the
 * end comes into view; the controller ignores the ask while it is busy, and
 * what it loads changes the count, which asks again until the screen is full.
 */
@Composable
private fun Timeline(
    months: List<TimelineMonth>,
    more: Boolean,
    controller: BrowserController,
    cover: suspend (DavResource) -> ImageBitmap?,
    videos: VideoController?,
    selected: Set<String>,
) {
    val grid = rememberLazyGridState()
    LaunchedEffect(grid, controller) {
        snapshotFlow {
            val layout = grid.layoutInfo
            (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) to layout.totalItemsCount
        }.collect { (last, total) -> if (last >= total - AHEAD) controller.loadMore() }
    }
    LazyVerticalGrid(
        GridCells.Adaptive(104.dp),
        Modifier.fillMaxSize(),
        state = grid,
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        months.forEach { month ->
            item(key = month.path, span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    monthName(month),
                    Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(month.entries, key = { it.path }) { entry ->
                Tile(
                    entry, controller, cover,
                    onPlay = videos?.takeIf { it.canPlay(entry) }?.let { v -> { v.play(entry) } },
                    selected = entry.path in selected,
                    selecting = selected.isNotEmpty(),
                    named = false,
                )
            }
        }
        if (more) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
        }
    }
}

/** Tiles still to come below the screen when the next month is asked for. */
private const val AHEAD = 12

private val MONTHS = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

private fun monthName(month: TimelineMonth) =
    "${MONTHS.getOrNull(month.month - 1) ?: month.month} ${month.year}"

/**
 * A folder as a square: its picture, or its mark where it has none, and its
 * name under it -- the web's album grid. Tapping walks in, as a row does.
 */
@Composable
private fun Tile(
    entry: DavResource,
    controller: BrowserController,
    cover: suspend (DavResource) -> ImageBitmap?,
    onPlay: (() -> Unit)? = null,
    selected: Boolean = false,
    selecting: Boolean = false,
    /** Whether the name is written under it, which a timeline's tiles are not, as the web's are not. */
    named: Boolean = true,
) {
    val picture by produceState<ImageBitmap?>(null, entry.path) { value = cover(entry) }
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.combinedClickable(
            onClick = {
                when {
                    selecting -> controller.toggle(entry)
                    entry.isDirectory -> controller.enter(entry)
                    onPlay != null -> onPlay()
                    isPicture(entry) -> controller.view(entry)
                    else -> controller.openFile(entry)
                }
            },
            onLongClick = { controller.toggle(entry) },
        ),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, shape),
            contentAlignment = Alignment.Center,
        ) {
            val shown = picture
            if (shown != null) {
                Image(shown, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Symbol(markOf(entry), Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.displaySmall)
            }
            if (!named && onPlay != null) {
                Symbol(
                    Glyph.PLAY,
                    Modifier.align(Alignment.BottomStart).padding(4.dp).clearAndSetSemantics {},
                    color = Color.White,
                )
            }
            if (selected) {
                Symbol(
                    Glyph.SELECTED,
                    Modifier.align(Alignment.TopEnd).padding(6.dp).clearAndSetSemantics {},
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (named) {
            Text(entry.name, Modifier.padding(top = 6.dp), maxLines = 2, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * One file or folder, with everything that can be done to it.
 *
 * Internal rather than private because the search results are these rows
 * (stratus-app#143): a file found is the same file, and a second kind of row
 * would be a second set of things it can do. [onEnter] is what tapping a
 * folder does, which differs by where the row is -- in a listing it walks in,
 * and in a result it leaves the search behind first.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Entry(
    entry: DavResource,
    controller: BrowserController,
    cast: CastController?,
    onEnter: (DavResource) -> Unit = controller::enter,
    /** Plays this track and what follows it, where it is one. */
    onPlay: (() -> Unit)? = null,
    playOnTap: Boolean = false,
    /** Opens this photograph in the viewer, among the listing's others unless told otherwise. */
    onView: () -> Unit = { controller.view(entry) },
    selected: Boolean = false,
    selecting: Boolean = false,
    /** Ticks or unticks this row; null where rows cannot be selected, such as search results. */
    onSelect: ((DavResource) -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }

    // The web UI's row: the kind on the left and every action behind one
    // gear on the right. Tapping and holding the row still open the same menu,
    // since the gear is a small target and the row is the whole width.
    ListItem(
        leadingContent = {
            if (selected) Symbol(Glyph.SELECTED, color = MaterialTheme.colorScheme.primary) else Symbol(markOf(entry))
        },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else ListItemDefaults.containerColor,
        ),
        headlineContent = { Text(entry.name) },
        supportingContent = { Text(if (entry.isDirectory) "Folder" else describe(entry)) },
        trailingContent = {
            // Anchored to the button rather than the row, so the menu opens
            // where the finger is, as the web's opens under its button.
            Box {
                // Named by its description and not by its text, which is a
                // private-use character nobody can read out.
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.semantics { contentDescription = "Actions on ${entry.name}" },
                ) { Symbol(Glyph.ACTIONS, Modifier.clearAndSetSemantics {}) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    Actions(entry, controller, cast, onPlay, onSelect, close = { menuOpen = false })
                }
            }
        },
        // A folder's menu had no way of opening at all, because tapping one
        // walks into it -- so renaming or deleting a folder was unreachable.
        // Holding is the gesture for "not the obvious thing", everywhere.
        modifier = Modifier.combinedClickable(
            onClick = {
                when {
                    selecting && onSelect != null -> onSelect(entry)
                    entry.isDirectory -> onEnter(entry)
                    playOnTap && onPlay != null -> onPlay()
                    isPicture(entry) -> onView()
                    else -> menuOpen = true
                }
            },
            // Holding selects, as it does in every Android file manager, where
            // there is a selection to make; the menu is the three dots.
            onLongClick = { if (onSelect != null) onSelect(entry) else menuOpen = true },
        ),
    )
}

@Composable
private fun Actions(
    entry: DavResource,
    controller: BrowserController,
    cast: CastController?,
    onPlay: (() -> Unit)?,
    onSelect: ((DavResource) -> Unit)?,
    close: () -> Unit,
) {
    if (onPlay != null) Action(Glyph.PLAY, "Play") { close(); onPlay() }
    if (onSelect != null) Action(Glyph.SELECTED, "Select") { close(); onSelect(entry) }
    if (!entry.isDirectory) {
        // Two different things: one shows the file, the other keeps it.
        Action(Glyph.OPEN, "Open") { close(); controller.openFile(entry) }
        Action(Glyph.DOWNLOAD, "Download") { close(); controller.saveFile(entry) }
        Action(Glyph.SEND, "Send the file") { close(); controller.sendFile(entry) }
    }
    if (cast?.canCast(entry) == true) {
        Action(Glyph.SCREEN, "Cast to a screen") { close(); cast.offer(entry) }
    }
    Action(Glyph.DETAILS, "Details") { close(); controller.ask(Confirmation.Details(entry)) }
    if (controller.canShare) {
        Action(Glyph.SHARE, "Share a link") { close(); controller.ask(Confirmation.Share(entry)) }
    }
    Action(Glyph.RENAME, "Rename") { close(); controller.ask(Confirmation.Rename(entry)) }
    HorizontalDivider()
    Action(Glyph.DELETE, "Delete", danger = true) { close(); controller.ask(Confirmation.Delete(entry)) }
}

/** A menu line: the mark, then the words, which are what a test and a screen reader go by. */
@Composable
private fun Action(glyph: String, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val colour = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        leadingIcon = { Symbol(glyph, Modifier.clearAndSetSemantics {}, color = colour) },
        text = { Text(label, color = colour) },
        onClick = onClick,
    )
}

/**
 * What kind of thing a row is, as the web UI's listing draws it. The type the
 * server reported first, and the name's extension where it reported none --
 * which a generic WebDAV server often does not.
 */
internal fun markOf(entry: DavResource): String {
    if (entry.isDirectory) return Glyph.FOLDER
    val type = entry.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    val ext = entry.name.substringAfterLast('.', "").lowercase()
    return when {
        type.startsWith("image/") || ext in photoExtensions -> Glyph.PHOTO
        type.startsWith("video/") || ext in filmExtensions -> Glyph.FILM
        type.startsWith("audio/") || ext in trackExtensions -> Glyph.TRACK
        type == "application/pdf" || ext == "pdf" -> Glyph.PDF
        ext in setOf("zip", "tar", "gz", "tgz", "bz2", "xz", "zst", "7z", "rar") -> Glyph.ARCHIVE
        ext in setOf("xls", "xlsx", "ods", "csv", "tsv") -> Glyph.SHEET
        ext == "ics" -> Glyph.EVENT
        ext == "vcf" -> Glyph.CONTACT
        else -> Glyph.DOCUMENT
    }
}

private val photoExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "tif", "tiff", "bmp", "dng", "avif")
private val filmExtensions = setOf("mp4", "m4v", "mov", "mkv", "webm", "avi", "wmv", "ts", "3gp")
private val trackExtensions = setOf("mp3", "m4a", "flac", "ogg", "opus", "wav", "aac", "alac", "wma", "aiff")

@Composable
private fun DeleteDialog(target: DavResource, controller: BrowserController) {
    AlertDialog(
        onDismissRequest = controller::dismiss,
        title = { Text("Delete ${target.name}?") },
        text = { Text(WHERE_DELETED_GOES) },
        confirmButton = { TextButton(onClick = controller::confirmDelete) { Text("Delete") } },
        dismissButton = { TextButton(onClick = controller::dismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DeleteSeveralDialog(targets: List<DavResource>, controller: BrowserController) {
    AlertDialog(
        onDismissRequest = controller::dismiss,
        title = { Text("Delete ${targets.size} items?") },
        text = { Text(WHERE_DELETED_GOES) },
        confirmButton = { TextButton(onClick = controller::confirmDeleteSeveral) { Text("Delete") } },
        dismissButton = { TextButton(onClick = controller::dismiss) { Text("Cancel") } },
    )
}

// What deleting means depends on the server, and the app only knows a Stratus
// by its sections, so it says both rather than guessing.
private const val WHERE_DELETED_GOES =
    "On a Stratus this goes to the trash. On other servers it cannot be undone."

@Composable
private fun RenameDialog(target: DavResource, controller: BrowserController) {
    var name by remember(target.path) { mutableStateOf(target.name) }
    AlertDialog(
        onDismissRequest = controller::dismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { controller.confirmRename(name) }) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = controller::dismiss) { Text("Cancel") } },
    )
}

private fun describe(entry: DavResource): String {
    val size = entry.size ?: return entry.contentType ?: ""
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
        else -> "${size / (1024 * 1024 * 1024)} GB"
    }
}

/**
 * The web UI's Details, as far as WebDAV reaches: the file's own half of it.
 * What the server's indexer has read -- a camera, a codec, a track's tags --
 * has no standard property to travel in, so it is not here.
 */
@Composable
private fun DetailsDialog(target: DavResource, controller: BrowserController) {
    val facts = buildList {
        add("Type" to (if (target.isDirectory) "Folder" else target.contentType))
        target.size?.takeIf { !target.isDirectory }?.let { add("Size" to "${size(it)} ($it bytes)") }
        add("Modified" to target.lastModifiedEpochMs?.let(::dateTime))
        add("ETag" to target.etag)
        add("Path" to target.path)
        add("Stored at" to target.source)
    }.mapNotNull { (label, value) -> value?.takeIf { it.isNotBlank() }?.let { label to it } }

    AlertDialog(
        onDismissRequest = controller::dismiss,
        title = { Text(target.name) },
        text = {
            // Selectable, because a path and an ETag are things to copy.
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((label, value) in facts) {
                        Column {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = controller::dismiss) { Text("Close") } },
    )
}

/**
 * The four lifetimes, and the sentence that has to be said out loud.
 *
 * None of this can be taken back one link at a time, so the screen that gives a
 * link away is the only place somebody can be told so.
 */
@Composable
private fun ShareDialog(target: DavResource, controller: BrowserController) {
    AlertDialog(
        onDismissRequest = controller::dismiss,
        title = { Text("Share \"${target.name}\"") },
        text = {
            Text(
                "Anyone with the link can open it, without signing in" +
                    (if (target.isDirectory) ", and everything inside it" else "") + ".\n\n" +
                    "There is no list of what you have shared and no way to withdraw one link: " +
                    "changing your password withdraws them all. Renaming this breaks its link.",
            )
        },
        confirmButton = {
            Column {
                for (life in ShareLife.entries) {
                    TextButton(onClick = { controller.confirmShare(life) }) { Text(label(life)) }
                }
            }
        },
        dismissButton = { TextButton(onClick = controller::dismiss) { Text("Cancel") } },
    )
}

private fun label(life: ShareLife) = when (life) {
    ShareLife.ADay -> "For a day"
    ShareLife.AWeek -> "For a week"
    ShareLife.AMonth -> "For a month"
    ShareLife.Forever -> "Until I change my password"
}

/**
 * Everything casting has to say, which is a warning, a list of screens, or what
 * is playing.
 */
@Composable
private fun CastBar(cast: CastController) {
    val state by cast.state.collectAsState()
    val devices by cast.devices.collectAsState()

    when (val here = state) {
        CastState.Idle -> {}

        is CastState.Playing -> CastControls(cast, here)

        // Told rather than refused: a pin is only consulted when the system's
        // own check fails, so a server given a real certificate since would be
        // warned about for nothing.
        is CastState.CertificateIsOnlyTrustedHere -> AlertDialog(
            onDismissRequest = cast::stop,
            title = { Text("A television cannot check this certificate") },
            text = {
                Text(
                    "You accepted this server's certificate on this phone, and nothing else " +
                        "vouches for it. A television has nobody to ask, so it will most likely " +
                        "fetch nothing and sit there.\n\n" +
                        "What fixes it: a certificate from a real authority — Let's Encrypt is " +
                        "free — or reaching the server over plain http on your own network.",
                )
            },
            confirmButton = { TextButton(onClick = cast::goOnAnyway) { Text("Cast anyway") } },
            dismissButton = { TextButton(onClick = cast::stop) { Text("Cancel") } },
        )

        CastState.TheServerDoesNotDoLinks -> AlertDialog(
            onDismissRequest = cast::stop,
            title = { Text("This server cannot hand a link to a television") },
            text = {
                Text(
                    "A Chromecast fetches the file itself and cannot sign in, so it needs a link " +
                        "that carries its own permission. Stratus makes those; this server does not.",
                )
            },
            confirmButton = { TextButton(onClick = cast::stop) { Text("Close") } },
        )

        is CastState.ScreenCannotPlay -> AlertDialog(
            onDismissRequest = cast::stop,
            title = { Text("${here.device.name} cannot play this") },
            text = {
                Text(
                    "It lists what it plays, and none of it is what there is to send: " +
                        here.offered.joinToString(" or ") + ".",
                )
            },
            confirmButton = { TextButton(onClick = cast::stop) { Text("Close") } },
        )

        is CastState.ScreenFailed -> AlertDialog(
            onDismissRequest = cast::stop,
            title = { Text("${here.device.name} did not play it") },
            text = { Text(here.reason) },
            confirmButton = { TextButton(onClick = cast::stop) { Text("Close") } },
        )

        CastState.TheServerDidNotAnswer -> AlertDialog(
            onDismissRequest = cast::stop,
            title = { Text("The server did not answer") },
            text = { Text("It may be restarting or out of reach. Try again in a moment.") },
            confirmButton = { TextButton(onClick = cast::stop) { Text("Close") } },
        )

        is CastState.Choosing -> AlertDialog(
            onDismissRequest = cast::stop,
            title = { Text("Cast \"${here.item.title}\"") },
            text = {
                Column {
                    if (devices.isEmpty()) {
                        Text("Looking for screens on this network…")
                    } else {
                        for (device in devices) {
                            TextButton(onClick = { cast.playOn(device) }) { ScreenName(cast, device) }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = cast::stop) { Text("Cancel") } },
        )
    }
}

private fun BrowserFailure.isRejectedSignIn() =
    (this is BrowserFailure.Listing && error is DavError.Unauthorized) ||
        (this is BrowserFailure.Operation && error is DavError.Unauthorized)

/**
 * A screen's name, with its own picture of itself in front where it has one
 * -- which is how somebody tells two televisions apart before they have read
 * either name. Fetched once per address by the caster; until it arrives, and
 * for a screen with none -- every Chromecast, whose SDK hands over no icon --
 * a television glyph holds the same square, so the names stay in a column.
 */
@Composable
private fun ScreenName(cast: CastController, device: CastDevice) {
    val picture by produceState<ImageBitmap?>(null, device) {
        value = cast.iconOf(device)?.let(::decodePicture)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            val drawn = picture
            if (drawn != null) {
                Image(drawn, contentDescription = null, modifier = Modifier.size(32.dp))
            } else {
                Symbol(Glyph.SCREEN)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(device.name)
    }
}

