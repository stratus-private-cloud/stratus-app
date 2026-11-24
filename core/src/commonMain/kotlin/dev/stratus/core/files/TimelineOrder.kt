package dev.stratus.core.files

import dev.stratus.core.dav.DavResource

/**
 * A month's files in the order the web's gallery shows them: by when the
 * camera says, or by when the file arrived where it says nothing -- which is
 * the server's `sort_at`, so the two agree photograph for photograph. By name
 * it agreed only for what this app's own backup named, whose names begin with
 * the moment; a screenshot or a file sent from another phone landed elsewhere.
 *
 * The camera's clock has no zone and is compared with the arrival as if it
 * were UTC, because that is what the server does.
 */
internal fun newestTakenFirst(listing: List<DavResource>): List<DavResource> =
    listing.filter { !it.isDirectory }.sortedWith(
        compareByDescending<DavResource> { it.taken?.let(::wallClockEpochMs) ?: it.lastModifiedEpochMs ?: Long.MIN_VALUE }
            .thenByDescending { it.name },
    )

/** `2026-11-20T14:30:22` read as UTC, or null for anything else. */
internal fun wallClockEpochMs(taken: String): Long? {
    val match = WALL_CLOCK.matchEntire(taken.trim()) ?: return null
    val (y, mo, d, h, mi, s) = match.destructured
    val days = daysFromCivil(y.toLong(), mo.toInt(), d.toInt())
    return ((days * 24 + h.toLong()) * 60 + mi.toLong()) * 60_000 + s.toLong() * 1000
}

private val WALL_CLOCK = Regex("""(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})""")

// Howard Hinnant's days_from_civil: days since 1970-01-01 in the proleptic
// Gregorian calendar, with no date library to ask.
private fun daysFromCivil(year: Long, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
}
