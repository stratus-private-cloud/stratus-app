package dev.stratus.core.play

import dev.stratus.core.net.TrustPolicy
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.addPeriodicTimeObserverForInterval
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSData
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.dataWithBytes
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPMediaItemArtwork
import platform.MediaPlayer.MPMediaItemPropertyAlbumTitle
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyArtwork
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.UIKit.UIImage

/**
 * The system's player and the system's lock screen.
 *
 * An `AVPlayer` and a queue of our own rather than `AVQueuePlayer`, which
 * forgets each track as it finishes and so cannot go back to one. The audio
 * session's playback category is what keeps it playing with the screen
 * locked, together with the `audio` background mode in the Info.plist.
 *
 * What it cannot do is trust a certificate only this phone vouches for:
 * `AVPlayer` fetches through the system, and the system says no. [trust] is
 * accepted and unused, which is the one way this side is weaker than Android's.
 */
@OptIn(ExperimentalForeignApi::class)
class IosPlayer : Player {
    private val player = AVPlayer()
    private val mutable = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = mutable.asStateFlow()

    private var queue: List<Track> = emptyList()
    private var index = -1

    init {
        NSNotificationCenter.defaultCenter.addObserverForName(
            AVPlayerItemDidPlayToEndTimeNotification, `object` = null, queue = NSOperationQueue.mainQueue,
        ) { _ -> if (index + 1 < queue.size) load(index + 1) else finish() }

        // Twice a second: what the mini player and the lock screen show.
        player.addPeriodicTimeObserverForInterval(CMTimeMakeWithSeconds(0.5, 1000), queue = null) { _ -> publish() }

        val commands = MPRemoteCommandCenter.sharedCommandCenter()
        commands.playCommand.addTargetWithHandler { _ -> player.play(); publish(); MPRemoteCommandHandlerStatusSuccess }
        commands.pauseCommand.addTargetWithHandler { _ -> player.pause(); publish(); MPRemoteCommandHandlerStatusSuccess }
        commands.togglePlayPauseCommand.addTargetWithHandler { _ -> toggle(); MPRemoteCommandHandlerStatusSuccess }
        commands.nextTrackCommand.addTargetWithHandler { _ -> next(); MPRemoteCommandHandlerStatusSuccess }
        commands.previousTrackCommand.addTargetWithHandler { _ -> previous(); MPRemoteCommandHandlerStatusSuccess }
        commands.changePlaybackPositionCommand.addTargetWithHandler { event ->
            (event as? MPChangePlaybackPositionCommandEvent)?.let { seekTo((it.positionTime * 1000).toLong()) }
            MPRemoteCommandHandlerStatusSuccess
        }
    }

    override fun position(): Pair<Long, Long?> {
        val at = CMTimeGetSeconds(player.currentTime())
        val length = player.currentItem?.let { CMTimeGetSeconds(it.duration) }
        return (if (at.isNaN()) 0L else (at * 1000).toLong()) to length?.takeUnless { it.isNaN() }?.let { (it * 1000).toLong() }
    }

    override fun play(queue: List<Track>, start: Int, trust: TrustPolicy) {
        AVAudioSession.sharedInstance().apply {
            setCategory(AVAudioSessionCategoryPlayback, null)
            setActive(true, null)
        }
        this.queue = queue
        load(start)
    }

    override fun toggle() {
        if (player.rate > 0f) player.pause() else player.play()
        publish()
    }

    override fun next() {
        if (index + 1 < queue.size) load(index + 1)
    }

    // The way every player does it: back to the start of this one, unless it
    // has only just begun.
    override fun previous() {
        if (position().first > RESTART_MS || index == 0) seekTo(0) else load(index - 1)
    }

    override fun seekTo(ms: Long) {
        player.seekToTime(CMTimeMakeWithSeconds(ms / 1000.0, 1000))
        publish()
    }

    override fun stop() {
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
        queue = emptyList()
        index = -1
        publish()
    }

    private fun load(at: Int) {
        val track = queue.getOrNull(at) ?: return
        val url = NSURL.URLWithString(track.url) ?: return
        index = at
        player.replaceCurrentItemWithPlayerItem(AVPlayerItem(uRL = url))
        player.play()
        publish()
    }

    private fun finish() {
        player.pause()
        publish()
    }

    private fun publish() {
        val item = player.currentItem
        mutable.value = PlayerState(
            queue = queue,
            index = index,
            playing = player.rate > 0f,
            failure = item?.takeIf { it.status == AVPlayerItemStatusFailed }?.error?.localizedDescription,
        )
        nowPlaying()
    }

    private fun nowPlaying() {
        val track = queue.getOrNull(index)
        val center = MPNowPlayingInfoCenter.defaultCenter()
        if (track == null) {
            center.nowPlayingInfo = null
            return
        }
        val (at, length) = position()
        val info = mutableMapOf<Any?, Any?>(
            MPMediaItemPropertyTitle to track.title,
            MPNowPlayingInfoPropertyElapsedPlaybackTime to at / 1000.0,
            MPNowPlayingInfoPropertyPlaybackRate to player.rate.toDouble(),
        )
        track.artist?.let { info[MPMediaItemPropertyArtist] = it }
        track.album?.let { info[MPMediaItemPropertyAlbumTitle] = it }
        length?.let { info[MPMediaItemPropertyPlaybackDuration] = it / 1000.0 }
        artworkOf(track)?.let { info[MPMediaItemPropertyArtwork] = it }
        center.nowPlayingInfo = info
    }

    // Made once per album rather than twice a second.
    private var artwork: Pair<ByteArray, MPMediaItemArtwork>? = null

    private fun artworkOf(track: Track): MPMediaItemArtwork? {
        val bytes = track.artwork ?: return null
        artwork?.let { (made, art) -> if (made === bytes) return art }
        val data = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.convert()) }
        val image = UIImage.imageWithData(data) ?: return null
        return MPMediaItemArtwork(boundsSize = image.size, requestHandler = { _ -> image }).also { artwork = bytes to it }
    }

    private companion object {
        const val RESTART_MS = 3_000L
    }
}
