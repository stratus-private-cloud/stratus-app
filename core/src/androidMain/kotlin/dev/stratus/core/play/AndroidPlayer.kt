package dev.stratus.core.play

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as Media3Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.stratus.core.net.TrustPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's half of [PlaybackService]: a controller connected to its session.
 *
 * The queue is read back from the session rather than kept here, so that an
 * app reopened while music plays shows what is playing rather than nothing.
 * Everything here runs on the main thread, which is where a controller lives.
 */
class AndroidPlayer(context: Context) : Player {
    private val context = context.applicationContext
    private val mutable = MutableStateFlow(PlayerState())
    private val published = mutable.asStateFlow()

    // Asking is what connects: a screen that wants to show the player is the
    // moment to find out whether something is already playing, and the
    // backup worker, which shares this container, never asks.
    override val state: StateFlow<PlayerState>
        get() = published.also { withController {} }

    private var controller: MediaController? = null
    private var connecting = false
    private val waiting = mutableListOf<(MediaController) -> Unit>()

    private val listener = object : Media3Player.Listener {
        override fun onEvents(player: Media3Player, events: Media3Player.Events) = publish(player)
    }

    override fun position(): Pair<Long, Long?> {
        val c = controller ?: return 0L to null
        return c.currentPosition to c.duration.takeIf { it != C.TIME_UNSET }
    }

    override fun play(queue: List<Track>, start: Int, trust: TrustPolicy) {
        PlaybackTrust.policy = trust
        withController { c ->
            c.setMediaItems(queue.map(::itemOf), start, 0L)
            c.prepare()
            c.play()
        }
    }

    override fun toggle() = withController { if (it.isPlaying) it.pause() else it.play() }
    override fun next() = withController { it.seekToNextMediaItem() }
    override fun previous() = withController { it.seekToPreviousMediaItem() }
    override fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    override fun stop() = withController {
        it.stop()
        it.clearMediaItems()
    }

    /** Connects once, and runs [block] when there is something to run it on. */
    private fun withController(block: (MediaController) -> Unit) {
        controller?.let { return block(it) }
        waiting += block
        if (connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            connecting = false
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener waiting.clear()
            controller = c
            c.addListener(listener)
            publish(c)
            waiting.toList().also { waiting.clear() }.forEach { it(c) }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun publish(player: Media3Player) {
        val queue = (0 until player.mediaItemCount).map { trackOf(player.getMediaItemAt(it)) }
        mutable.value = PlayerState(
            queue = queue,
            index = if (queue.isEmpty()) -1 else player.currentMediaItemIndex,
            playing = player.isPlaying || (player.playWhenReady && player.playbackState == Media3Player.STATE_BUFFERING),
            failure = player.playerError?.let(::explain),
        )
    }

    private fun explain(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Could not reach the server"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server would not hand this over"
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "This phone cannot play this file"
        else -> error.errorCodeName
    }

    private fun itemOf(track: Track): MediaItem = MediaItem.Builder()
        .setMediaId(track.url)
        .setUri(Uri.parse(track.url))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setAlbumTitle(track.album)
                .setArtist(track.artist)
                .apply { track.artwork?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
                .build(),
        )
        .build()

    private fun trackOf(item: MediaItem) = Track(
        url = item.mediaId,
        title = item.mediaMetadata.title?.toString().orEmpty(),
        album = item.mediaMetadata.albumTitle?.toString(),
        artist = item.mediaMetadata.artist?.toString(),
    )
}
