package dev.stratus.core.play

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
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
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

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
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    // Swiping the app away stops the music only when none is playing: a
    // player that dies with the task list is not one that plays in a pocket.
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
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
