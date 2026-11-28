package dev.stratus.core.media

import dev.stratus.core.dav.DavClient
import dev.stratus.core.dav.DavError
import dev.stratus.core.dav.DavResource
import kotlin.random.Random

/** A photograph from an earlier year, and what to call it. */
data class Memory(val photo: DavResource, val yearsAgo: Int, val onTheDay: Boolean) {
    val caption: String
        get() {
            val ago = if (yearsAgo == 1) "A year ago" else "$yearsAgo years ago"
            return if (onTheDay) "$ago today" else "This month, ${ago.lowercase()}"
        }
}

/**
 * Today's photograph from an earlier year, read from a Stratus's `photos/`
 * timeline (stratus-app#266): one taken on this day where there is one, else
 * one from this month. The same one all day, so a widget that redraws does not
 * shuffle.
 *
 * Null where there is nothing to show, including a server with no timeline:
 * anything but a Stratus answers `photos/` with an error, and that is the same
 * as an empty one. A failure further in is thrown, since it says nothing about
 * whether there would have been a photograph.
 */
suspend fun memoryOn(dav: DavClient, year: Int, month: Int, day: Int, floor: String = "/photos/"): Memory? {
    val years = try {
        dav.list(floor)
    } catch (_: DavError) {
        return null
    }.filter { it.isDirectory }
        .mapNotNull { folder -> folder.name.toIntOrNull()?.takeIf { it in year - YEARS_BACK until year }?.to(folder.path) }

    val photos = years.flatMap { (y, path) ->
        // By number rather than by name, so `3` and `03` are both March.
        dav.list(path).filter { it.isDirectory && it.name.toIntOrNull() == month }
            .flatMap { dav.list(it.path, taken = true) }
            .filter { !it.isDirectory }
            .map { y to it }
    }
    val onTheDay = photos.filter { (_, photo) -> photo.taken?.let(::dayOf) == day }
    val pool = onTheDay.ifEmpty { photos }.sortedBy { (_, photo) -> photo.path }
    if (pool.isEmpty()) return null
    val (taken, photo) = pool[Random(year * 10_000 + month * 100 + day).nextInt(pool.size)]
    return Memory(photo, year - taken, onTheDay.isNotEmpty())
}

/** The day of the month of the camera's `2026-11-20T14:30:22`. */
private fun dayOf(taken: String): Int? = taken.trim().takeIf { it.length >= 10 }?.substring(8, 10)?.toIntOrNull()

private const val YEARS_BACK = 10
