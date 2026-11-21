package dev.stratus.core.files

import dev.stratus.core.dav.DavResource
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeopleTest {
    private fun person(name: String) = DavResource("/people/$name", isDirectory = true)

    @Test
    fun theNamedComeFirstWhateverTheShuffle() {
        val listing = listOf(person("Ana"), person("Person 12"), person("Person 3"), person("Zoe"))
        repeat(20) { seed ->
            val shown = bannerPeople(listing, most = 3, random = Random(seed)).map { it.name }
            assertEquals(setOf("Ana", "Zoe"), shown.take(2).toSet())
            assertTrue(shown[2] in setOf("Person 12", "Person 3"))
        }
    }

    @Test
    fun everybodyGetsTheirTurn() {
        val listing = (1..10).map { person("Name $it") }
        val seen = (0 until 50).flatMap { bannerPeople(listing, random = Random(it)) }.map { it.name }.toSet()
        assertEquals(listing.map { it.name }.toSet(), seen)
    }

    @Test
    fun aFileIsNobody() {
        val listing = listOf(DavResource("/people/notes.txt", isDirectory = false), person("Ana"))
        assertEquals(listOf("Ana"), bannerPeople(listing).map { it.name })
    }

    @Test
    fun aNameThatOnlyBeginsLikeTheServersIsAName() {
        val listing = listOf(person("Person 4"), person("Person of interest"))
        assertEquals(listOf("Person of interest", "Person 4"), bannerPeople(listing).map { it.name })
    }
}
