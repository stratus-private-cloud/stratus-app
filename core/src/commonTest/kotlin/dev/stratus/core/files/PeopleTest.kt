package dev.stratus.core.files

import dev.stratus.core.dav.DavResource
import kotlin.test.Test
import kotlin.test.assertEquals

class PeopleTest {
    private fun person(name: String) = DavResource("/people/$name", isDirectory = true)

    @Test
    fun theNamedComeFirstInTheListingsOrder() {
        val listing = listOf(person("Ana"), person("Person 12"), person("Person 3"), person("Zoe"))
        assertEquals(listOf("Ana", "Zoe", "Person 12"), bannerPeople(listing, most = 3).map { it.name })
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
