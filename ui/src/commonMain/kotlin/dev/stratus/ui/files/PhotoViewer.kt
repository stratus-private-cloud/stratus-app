package dev.stratus.ui.files

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.stratus.core.dav.DavResource
import dev.stratus.core.files.BrowserController
import dev.stratus.core.files.Confirmation
import dev.stratus.core.files.Original
import dev.stratus.core.files.Viewing
import dev.stratus.ui.Glyph
import dev.stratus.ui.Symbol
import dev.stratus.ui.decodeOriginal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A folder's photographs, one at a time and full screen, a swipe apart.
 *
 * What is drawn is the original, downloaded and decoded here, and never the
 * server's rendering of it: that is a Stratus's alone, and at 1200 pixels it
 * is not the photograph somebody zooms into.
 */
@Composable
internal fun PhotoViewer(viewing: Viewing, controller: BrowserController) {
    val state by controller.state.collectAsState()
    val pager = rememberPagerState(initialPage = viewing.at) { viewing.among.size }
    var chrome by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    val shown = viewing.among[pager.currentPage]

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // One either side, so the next photograph is usually there before the
        // swipe that shows it.
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { viewing.among[it].path }) { page ->
            Photo(viewing.among[page], controller, onTap = { chrome = !chrome })
        }
        if (chrome) {
            Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f))) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { controller.stopViewing() }) { Symbol(Glyph.BACK, color = Color.White) }
                    Text(
                        shown.name,
                        Modifier.weight(1f),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box {
                        IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.semantics { contentDescription = "Actions on ${shown.name}" },
                        ) { Symbol(Glyph.ACTIONS, Modifier.clearAndSetSemantics {}, color = Color.White) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            ViewerAction(Glyph.OPEN, "Open in another app") { menuOpen = false; controller.openFile(shown) }
                            ViewerAction(Glyph.DOWNLOAD, "Download") { menuOpen = false; controller.saveFile(shown) }
                            if (controller.canShare) {
                                ViewerAction(Glyph.SHARE, "Share a link") {
                                    menuOpen = false
                                    controller.ask(Confirmation.Share(shown))
                                }
                            }
                        }
                    }
                }
                // Open and Download fetch the file again for somebody else,
                // and the listing's own bar is underneath this screen.
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ViewerAction(glyph: String, label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        leadingIcon = { Symbol(glyph, Modifier.clearAndSetSemantics {}) },
        text = { Text(label) },
        onClick = onClick,
    )
}

private sealed interface Shown {
    data object Loading : Shown
    class Ready(val image: ImageBitmap) : Shown
    data class Unshown(val why: String) : Shown
}

@Composable
private fun Photo(entry: DavResource, controller: BrowserController, onTap: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Twice the screen, so that zooming in has pixels to show, and no more
        // than that: a 48-megapixel original decoded whole is 190 MB.
        val longest = (maxOf(constraints.maxWidth, constraints.maxHeight) * 2).coerceIn(1, 4096)
        val shown by produceState<Shown>(Shown.Loading, entry.path) {
            value = when (val original = controller.original(entry)) {
                is Original.Bytes -> withContext(Dispatchers.Default) { decodeOriginal(original.bytes, longest) }
                    ?.let(Shown::Ready)
                    ?: Shown.Unshown("This phone cannot read this picture.")
                Original.TooLarge -> Shown.Unshown("This picture is too large to show here.")
                Original.Failed -> Shown.Unshown("The picture could not be downloaded.")
            }
        }
        when (val s = shown) {
            Shown.Loading -> CircularProgressIndicator(color = Color.White)
            is Shown.Ready -> Zoomable(s.image, onTap)
            is Shown.Unshown -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.why, color = Color.White)
                TextButton(onClick = { controller.openFile(entry) }) { Text("Open in another app") }
            }
        }
    }
}

/**
 * Pinch and double tap to zoom, and drag while zoomed. A single finger at
 * the photograph's own size is left alone, which is what lets the pager
 * underneath turn to the next one.
 */
@Composable
private fun Zoomable(image: ImageBitmap, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    fun bounded(proposed: Offset, at: Float): Offset {
        val x = size.width * (at - 1) / 2
        val y = size.height * (at - 1) / 2
        return Offset(proposed.x.coerceIn(-x, x), proposed.y.coerceIn(-y, y))
    }

    Image(
        image,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = DOUBLE_TAP_SCALE
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, MOST_SCALE)
                            offset = bounded(offset + event.calculatePan(), scale)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}

private const val DOUBLE_TAP_SCALE = 2.5f
private const val MOST_SCALE = 5f
