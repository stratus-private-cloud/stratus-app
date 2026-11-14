package dev.stratus.core.dav

/**
 * What a Stratus's indexer read out of a photograph or a recording, as the
 * properties it answers when they are named (stratus-backend#502) -- the web's
 * Details, over WebDAV. Every field is null where the file does not carry it,
 * which against any other server is every field.
 */
data class MediaInfo(
    /** The camera's clock as it recorded it, `2024-06-15T18:30:05`, with no zone because it has none. */
    val taken: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val camera: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val durationMs: Long? = null,
)
