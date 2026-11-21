package dev.stratus.core.files

import dev.stratus.core.dav.DavResource

/**
 * The people a gallery's banner shows, out of a listing of a Stratus's
 * `people/`: somebody with a name before somebody the server calls
 * `Person <id>`, each half in the listing's order, and at most [most].
 *
 * Telling the two apart by that spelling is Stratus's convention read from
 * the outside (stratus-backend#519), which is what this collection is: only a
 * Stratus at its origin has one, and anything else spelled that way is only
 * drawn later in a row of faces.
 */
fun bannerPeople(listing: List<DavResource>, most: Int = BANNER_PEOPLE): List<DavResource> =
    listing.filter { it.isDirectory }.sortedBy { UNNAMED.matches(it.name) }.take(most)

const val BANNER_PEOPLE = 5

private val UNNAMED = Regex("""Person \d+""")
