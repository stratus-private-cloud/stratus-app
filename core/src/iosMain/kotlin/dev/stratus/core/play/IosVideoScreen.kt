package dev.stratus.core.play

import dev.stratus.core.net.TrustPolicy
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.play
import platform.AVKit.AVPlayerViewController
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

/**
 * The system's own film player, presented over whatever the app is showing:
 * its controls, AirPlay and picture in picture come with it.
 *
 * There is no fallback here, unlike Android: what `AVPlayer` cannot open as a
 * file is what [videoSourceFor] already sends as HLS, which is Apple's own
 * format. And like the music, a certificate only this phone vouches for is
 * refused, since the player fetches with the system's trust.
 */
@OptIn(ExperimentalForeignApi::class)
class IosVideoScreen : VideoScreen {
    override fun show(source: VideoSource, trust: TrustPolicy) {
        val url = NSURL.URLWithString(source.url) ?: return
        val top = topmost() ?: return
        AVAudioSession.sharedInstance().apply {
            setCategory(AVAudioSessionCategoryPlayback, null)
            setActive(true, null)
        }
        val player = AVPlayer(playerItem = AVPlayerItem(uRL = url))
        val screen = AVPlayerViewController().apply { this.player = player }
        top.presentViewController(screen, animated = true) { player.play() }
    }

    private fun topmost(): UIViewController? {
        val window = UIApplication.sharedApplication.connectedScenes
            .filterIsInstance<UIWindowScene>()
            .flatMap { scene -> scene.windows.filterIsInstance<UIWindow>() }
            .firstOrNull { it.isKeyWindow() }
        var top = window?.rootViewController
        while (top?.presentedViewController != null) top = top.presentedViewController
        return top
    }
}
