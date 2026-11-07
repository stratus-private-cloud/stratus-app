package dev.stratus.core.play

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import dev.stratus.core.net.TrustPolicy

/**
 * A film, full screen, until back is pressed.
 *
 * An activity of its own rather than a composable: a player view is a
 * `View`, rotation is the activity's business, and leaving it is the system
 * back like everything else. Its own ExoPlayer rather than the music
 * session's -- a film is watched rather than listened to in a pocket -- and
 * taking the audio focus is what pauses the music.
 */
class VideoActivity : Activity() {
    private var player: ExoPlayer? = null
    private var fellBack = false

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(URL) ?: return finish()
        val fallback = intent.getStringExtra(FALLBACK)
        val title = intent.getStringExtra(TITLE).orEmpty()

        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(PlaybackTrust)))
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
            .build()
        player = exo

        val view = PlayerView(this).apply {
            this.player = exo
            keepScreenOn = true
            contentDescription = DESCRIPTION
        }
        setContentView(view)

        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // The phone could not decode what the file holds: the server's
                // HLS of the same film is made for exactly that, once.
                if (fellBack || fallback == null || !isFormat(error)) return
                fellBack = true
                val at = exo.currentPosition
                exo.setMediaItem(itemOf(fallback, title), at)
                exo.prepare()
                exo.play()
            }
        })
        exo.setMediaItem(itemOf(url, title))
        exo.prepare()
        exo.play()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun itemOf(url: String, title: String) = MediaItem.Builder()
        .setUri(url)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        .build()

    private fun isFormat(error: PlaybackException) = error.errorCode in setOf(
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    )

    companion object {
        internal const val URL = "url"
        internal const val FALLBACK = "fallback"
        internal const val TITLE = "title"

        /** What the player is named by, to a screen reader and to the end-to-end suite. */
        const val DESCRIPTION = "Video player"
    }
}

/** Opens [VideoActivity], trusting the server's certificate the way the rest of the app does. */
class AndroidVideoScreen(context: Context) : VideoScreen {
    private val context = context.applicationContext

    override fun show(source: VideoSource, trust: TrustPolicy) {
        PlaybackTrust.policy = trust
        context.startActivity(
            Intent(context, VideoActivity::class.java)
                .putExtra(VideoActivity.URL, source.url)
                .putExtra(VideoActivity.FALLBACK, source.fallback)
                .putExtra(VideoActivity.TITLE, source.title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
