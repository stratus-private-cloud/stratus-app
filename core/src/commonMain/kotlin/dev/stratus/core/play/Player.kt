package dev.stratus.core.play

import dev.stratus.core.net.TrustPolicy
import kotlinx.coroutines.flow.StateFlow

/**
 * One thing to play: a URL that needs no credentials, because it is signed,
 * and what the lock screen says about it.
 */
data class Track(
    val url: String,
    val title: String,
    val album: String? = null,
    val artist: String? = null,
    val contentType: String? = null,
    /** The album's cover as a JPEG, handed over as bytes: the lock screen cannot sign in to fetch one. */
    val artwork: ByteArray? = null,
) {
    // The artwork is left out on purpose: two tracks are the same track by
    // where they are, and comparing covers byte by byte says nothing more.
    override fun equals(other: Any?): Boolean =
        other is Track && url == other.url && title == other.title && album == other.album && artist == other.artist
    override fun hashCode(): Int = url.hashCode()
}

data class PlayerState(
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    /** Said in words, for a track that would not play. */
    val failure: String? = null,
) {
    val current: Track? get() = queue.getOrNull(index)
}

/**
 * The platform's player, which outlives any screen: on Android a media
 * session in a service of its own, on iOS the system's audio session. Both
 * keep playing with the screen locked and answer the lock screen's buttons.
 */
interface Player {
    val state: StateFlow<PlayerState>

    /** Milliseconds into the current track, and how long it is when that is known. Asked, not pushed. */
    fun position(): Pair<Long, Long?>

    /**
     * Replaces whatever was playing. [trust] is the server's certificate as
     * the rest of the app trusts it, for a platform whose player fetches with
     * a client of its own.
     */
    fun play(queue: List<Track>, start: Int, trust: TrustPolicy = TrustPolicy())
    fun toggle()
    fun next()
    fun previous()
    fun seekTo(ms: Long)
    fun stop()
}
