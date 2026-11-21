package dev.stratus.core.files

import dev.stratus.core.dav.DavResource
import kotlin.random.Random

/**
 * The people a gallery's banner shows, out of a listing of a Stratus's
 * `people/`: somebody with a name before somebody the server calls
 * `Person <id>`, each half shuffled so the banner is not the same few faces
 * every time, and at most [most].
 *
 * Telling the two apart by that spelling is Stratus's convention read from
 * the outside (stratus-backend#519), which is what this collection is: only a
 * Stratus at its origin has one, and anything else spelled that way is only
 * drawn later in a row of faces.
 */
fun bannerPeople(listing: List<DavResource>, most: Int = BANNER_PEOPLE, random: Random = Random): List<DavResource> {
    val (unnamed, named) = listing.filter { it.isDirectory }.partition { UNNAMED.matches(it.name) }
    return (named.shuffled(random) + unnamed.shuffled(random)).take(most)
}

const val BANNER_PEOPLE = 5

private val UNNAMED = Regex("""Person \d+""")
