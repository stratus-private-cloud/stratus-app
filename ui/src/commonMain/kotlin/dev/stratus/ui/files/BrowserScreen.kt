package dev.stratus.ui.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stratus.core.cast.CastController
import dev.stratus.core.cast.CastDevice
import dev.stratus.core.cast.CastState
import dev.stratus.core.dav.DavError
import dev.stratus.core.dav.DavResource
import dev.stratus.core.files.BrowserController
import dev.stratus.core.files.BrowserFailure
import dev.stratus.core.files.Confirmation
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
                    navigationIcon = {
                        if (onClose != null) {
                            IconButton(onClick = onClose) { Symbol(Glyph.BACK) }
                        }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(StratusMark, contentDescription = null, Modifier.size(22.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Stratus")
                        }
                    },
                    actions = {
                        if (onSearch != null) {
                            IconButton(onClick = onSearch) { Symbol(Glyph.SEARCH) }
                        }
                        IconButton(onClick = onOpenSettings) { Symbol(Glyph.SETTINGS) }
                    },
                )
            },
            bottomBar = bottomBar,
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                banner()
                Where(state.path, controller.floor, up = { controller.goUp() })
                if (state.busy && !pulled) LinearProgressIndicator(Modifier.fillMaxWidth())
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
                    if (cover != null && tiles(state.path)) {
                        LazyVerticalGrid(
                            GridCells.Adaptive(150.dp),
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(state.entries, key = { it.path }) { entry ->
                                Tile(entry, controller, cover, onPlay = videos?.takeIf { it.canPlay(entry) }?.let { v -> { v.play(entry) } })
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
        is Confirmation.Rename -> RenameDialog(pending.target, controller)
        is Confirmation.Share -> ShareDialog(pending.target, controller)
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
 * A folder as a square: its picture, or its mark where it has none, and its
 * name under it -- the web's album grid. Tapping walks in, as a row does.
 */
@Composable
private fun Tile(
    entry: DavResource,
    controller: BrowserController,
    cover: suspend (DavResource) -> ImageBitmap?,
    onPlay: (() -> Unit)? = null,
) {
    val picture by produceState<ImageBitmap?>(null, entry.path) { value = cover(entry) }
    Column(
        Modifier.clickable {
            when {
                entry.isDirectory -> controller.enter(entry)
                onPlay != null -> onPlay()
                isPicture(entry) -> controller.view(entry)
                else -> controller.openFile(entry)
            }
        },
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val shown = picture
            if (shown != null) {
                Image(shown, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Symbol(markOf(entry), Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.displaySmall)
            }
        }
        Text(entry.name, Modifier.padding(top = 6.dp), maxLines = 2, style = MaterialTheme.typography.bodyMedium)
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
) {
    var menuOpen by remember { mutableStateOf(false) }

    // The web UI's row: the kind on the left and every action behind one
    // gear on the right. Tapping and holding the row still open the same menu,
    // since the gear is a small target and the row is the whole width.
    ListItem(
        leadingContent = { Symbol(markOf(entry)) },
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
                    Actions(entry, controller, cast, onPlay, close = { menuOpen = false })
                }
            }
        },
        // A folder's menu had no way of opening at all, because tapping one
        // walks into it -- so renaming or deleting a folder was unreachable.
        // Holding is the gesture for "not the obvious thing", everywhere.
        modifier = Modifier.combinedClickable(
            onClick = {
                when {
                    entry.isDirectory -> onEnter(entry)
                    playOnTap && onPlay != null -> onPlay()
                    isPicture(entry) -> onView()
                    else -> menuOpen = true
                }
            },
            onLongClick = { menuOpen = true },
        ),
    )
}

@Composable
private fun Actions(
    entry: DavResource,
    controller: BrowserController,
    cast: CastController?,
    onPlay: (() -> Unit)?,
    close: () -> Unit,
) {
    if (onPlay != null) Action(Glyph.PLAY, "Play") { close(); onPlay() }
    if (!entry.isDirectory) {
        // Two different things: one shows the file, the other keeps it.
        Action(Glyph.OPEN, "Open") { close(); controller.openFile(entry) }
        Action(Glyph.DOWNLOAD, "Download") { close(); controller.saveFile(entry) }
    }
    if (cast?.canCast(entry) == true) {
        Action(Glyph.SCREEN, "Cast to a screen") { close(); cast.offer(entry) }
    }
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
        // There is no trash anywhere in Stratus, so this is the only chance.
        text = { Text("This cannot be undone. There is no trash to recover it from.") },
        confirmButton = { TextButton(onClick = controller::confirmDelete) { Text("Delete") } },
        dismissButton = { TextButton(onClick = controller::dismiss) { Text("Cancel") } },
    )
}

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

