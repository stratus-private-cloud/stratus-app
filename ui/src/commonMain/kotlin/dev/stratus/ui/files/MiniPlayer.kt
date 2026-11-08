package dev.stratus.ui.files

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.stratus.core.play.MusicController
import dev.stratus.ui.Glyph
import dev.stratus.ui.Symbol
import kotlinx.coroutines.delay

/**
 * What is playing, above the bottom bar for as long as anything is: the
 * track, how far into it, and the four buttons. Nothing at all otherwise.
 */
@Composable
fun MiniPlayer(music: MusicController) {
    val state by music.state.collectAsState()
    val track = state.current ?: return
    var progress by remember { mutableFloatStateOf(0f) }

    // Asked rather than pushed: a position changes continuously, and a flow of
    // it would be a recomposition per frame for a bar that moves a pixel a second.
    LaunchedEffect(track.url, state.playing) {
        while (true) {
            val (at, length) = music.position()
            progress = if (length == null || length <= 0) 0f else (at.toFloat() / length).coerceIn(0f, 1f)
            if (!state.playing) break
            delay(500)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider()
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                val under = state.failure ?: listOfNotNull(track.artist, track.album).joinToString(" · ")
                if (under.isNotEmpty()) {
                    Text(
                        under,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.failure != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(Glyph.PREVIOUS, "Previous", music::previous)
            Button(if (state.playing) Glyph.PAUSE else Glyph.PLAY, if (state.playing) "Pause" else "Play", music::toggle)
            Button(Glyph.NEXT, "Next", music::next)
            Button(Glyph.STOP, "Stop", music::stop)
        }
    }
}

/** Named by its word and not its mark, which a row's menu also draws. */
@Composable
private fun Button(glyph: String, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) {
        Symbol(glyph, Modifier.clearAndSetSemantics {})
    }
}
