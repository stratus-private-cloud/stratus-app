package dev.stratus.ui.files

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stratus.core.dav.DavResource
import dev.stratus.ui.Glyph
import dev.stratus.ui.Symbol

/**
 * A few of the people in the photographs, at the top of the gallery, and the
 * way to all of them: a face opens that person and the rest opens the list.
 */
@Composable
fun PeopleStrip(
    people: List<DavResource>,
    picture: suspend (DavResource) -> ImageBitmap?,
    onPerson: (DavResource) -> Unit,
    onAll: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onAll).padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        people.forEach { person -> Face(person, picture, onPerson) }
        Spacer(Modifier.weight(1f))
        Text("People", style = MaterialTheme.typography.titleMedium)
        Symbol(Glyph.MORE, Modifier.clearAndSetSemantics {})
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
private fun Face(person: DavResource, picture: suspend (DavResource) -> ImageBitmap?, onPerson: (DavResource) -> Unit) {
    val face by produceState<ImageBitmap?>(null, person.path) { value = picture(person) }
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onPerson(person) }
            .semantics { contentDescription = person.name },
        contentAlignment = Alignment.Center,
    ) {
        val shown = face
        if (shown != null) {
            Image(shown, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Symbol(Glyph.USERNAME, Modifier.clearAndSetSemantics {})
        }
    }
}
