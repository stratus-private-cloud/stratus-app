package dev.stratus.core.play

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dev.stratus.core.appContainer
import dev.stratus.core.media.Thumbnail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import dev.stratus.core.net.PinnedHostnameVerifier
import dev.stratus.core.net.PinningTrustManager
import dev.stratus.core.net.TrustPolicy
import dev.stratus.core.net.pinnedSocketFactory
import okhttp3.Call
import okhttp3.OkHttpClient
import javax.net.ssl.HttpsURLConnection

/**
 * The player, in a service of its own so that it outlives the activity: the
 * media session is what puts the notification up, answers the lock screen and
 * a headset, and keeps the process in the foreground while music plays.
 *
 * A library session rather than a plain one (stratus-app#267), because a
 * library is what Android Auto, a watch and the Assistant read: the tree and
 * what a chosen row plays are [MusicLibrary]'s, and what is here turns them
 * into Media3's types.
 */
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private val scope = MainScope()

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(PlaybackTrust)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            // Headphones pulled out is a pause, not a speaker announcing it to the room.
            .setHandleAudioBecomingNoisy(true)
            .build()
        session = MediaLibrarySession.Builder(this, player, Library()).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    // Swiping the app away stops the music only when none is playing: a
    // player that dies with the task list is not one that plays in a pocket.
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private inner class Library : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            LibraryResult.ofItem(folderOf(MusicLibrary.ROOT, "Stratus"), params),
        )

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = later {
            val (library, _) = appContainer(this@PlaybackService).musicLibrary()
                ?: return@later LibraryResult.ofError(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED)
            val rows = library.children(parentId).drop(page * pageSize).take(pageSize)
            val thumbnails = appContainer(this@PlaybackService).thumbnails
            val items = coroutineScope {
                rows.map { row ->
                    async {
                        val cover = row.coverOf?.takeIf { row.browsable }
                            ?.let { (thumbnails.cover(it, COVER_PIXELS) as? Thumbnail.Picture)?.bytes }
                        itemOf(row, cover)
                    }
                }.awaitAll()
            }
            LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
        }

        // Asked for one row by its id, which says what it is without a listing.
        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            LibraryResult.ofItem(folderOf(mediaId, mediaId.trimEnd('/').substringAfterLast('/')), null),
        )

        /**
         * A row chosen in a car is an id and nothing else, and a request to
         * play by voice is a query; the app's own queue arrives with its
         * links and is played as it is.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (mediaItems.all { it.localConfiguration != null }) {
                return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
            }
            val chosen = mediaItems.getOrNull(startIndex.coerceAtLeast(0)) ?: mediaItems.first()
            return later {
                val (library, trust) = appContainer(this@PlaybackService).musicLibrary()
                    ?: throw IllegalStateException("nobody is signed in")
                PlaybackTrust.policy = trust
                val query = chosen.requestMetadata.searchQuery
                val (tracks, start) = if (query != null) {
                    library.search(query) to 0
                } else {
                    library.queue(chosen.mediaId) ?: (emptyList<Track>() to 0)
                }
                MediaSession.MediaItemsWithStartPosition(tracks.map(::mediaItemOf), start, C.TIME_UNSET)
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            if (mediaItems.all { it.localConfiguration != null }) return Futures.immediateFuture(mediaItems)
            return later {
                val (library, trust) = appContainer(this@PlaybackService).musicLibrary()
                    ?: throw IllegalStateException("nobody is signed in")
                PlaybackTrust.policy = trust
                mediaItems.flatMap { item ->
                    when {
                        item.localConfiguration != null -> listOf(item)
                        item.requestMetadata.searchQuery != null ->
                            library.search(item.requestMetadata.searchQuery!!).map(::mediaItemOf)
                        else -> library.queue(item.mediaId)?.let { (tracks, start) -> listOf(mediaItemOf(tracks[start])) }.orEmpty()
                    }
                }.toMutableList()
            }
        }
    }

    /** Runs [block] on the main thread and answers with what it returned, or failed with. */
    private fun <T> later(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        scope.launch {
            try {
                future.set(block())
            } catch (e: CancellationException) {
                future.cancel(false)
                throw e
            } catch (e: Exception) {
                future.setException(e)
            }
        }
        return future
    }

    private fun folderOf(id: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build(),
        )
        .build()

    private fun itemOf(row: LibraryItem, cover: ByteArray?): MediaItem = MediaItem.Builder()
        .setMediaId(row.id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(row.title)
                .setArtist(row.artist)
                .setAlbumTitle(row.album)
                .setIsBrowsable(row.browsable)
                .setIsPlayable(row.playable)
                .setMediaType(
                    when {
                        !row.browsable -> MediaMetadata.MEDIA_TYPE_MUSIC
                        row.coverOf != null -> MediaMetadata.MEDIA_TYPE_ALBUM
                        row.playable -> MediaMetadata.MEDIA_TYPE_PLAYLIST
                        else -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                    },
                )
                .apply { cover?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
                .build(),
        )
        .build()

    private companion object {
        const val COVER_PIXELS = 300
    }
}

/**
 * The client the player fetches with, trusting the server's certificate the
 * way the rest of the app does. A server whose certificate only this phone
 * vouches for is the ordinary self-hosted case, and the platform's own client
 * would refuse it.
 *
 * Set before every queue, because the service is built before anything is
 * known about which server it will be playing from.
 */
internal object PlaybackTrust : Call.Factory {
    @Volatile private var current: Pair<String?, OkHttpClient>? = null

    @Volatile var policy: TrustPolicy = TrustPolicy()

    override fun newCall(request: okhttp3.Request): Call = client().newCall(request)

    private fun client(): OkHttpClient {
        val wanted = policy
        current?.let { (pin, client) -> if (pin == wanted.pin) return client }
        val trust = PinningTrustManager(wanted)
        val client = OkHttpClient.Builder()
            .sslSocketFactory(pinnedSocketFactory(trust), trust)
            .hostnameVerifier(PinnedHostnameVerifier(wanted, HttpsURLConnection.getDefaultHostnameVerifier()))
            .build()
        current = wanted.pin to client
        return client
    }
}
