package dev.stratus.ui

import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationShortcutIcon
import platform.UIKit.UIApplicationShortcutItem
import platform.UIKit.shortcutItems
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * A Stratus's parts on the icon's long press, Android's shortcuts again
 * (stratus-app#199). One per process, because the system hands the part picked
 * to a scene delegate in Swift and the screens read it here.
 */
internal val iosShortcuts = Shortcuts().apply {
    offer = { sections ->
        val items = sections.map { section ->
            UIApplicationShortcutItem(
                type = section.id,
                localizedTitle = section.label,
                localizedSubtitle = null,
                icon = SYMBOLS[section.id]?.let { UIApplicationShortcutIcon.iconWithSystemImageName(it) },
                userInfo = null,
            )
        }
        // UIKit's, and only on its own thread.
        dispatch_async(dispatch_get_main_queue()) { UIApplication.sharedApplication.shortcutItems = items }
    }
}

/**
 * The part a quick action was picked for, from Swift: at launch, out of the
 * scene's connection options, and while running, from the scene delegate.
 * A part asked for before the server has answered is held until it has, as on
 * Android.
 */
@Suppress("unused") // Called from Swift.
fun openShortcut(type: String) {
    iosShortcuts.opened.value = type
}

/** SF Symbols, the platform's own marks, for the parts Android draws its own icons for. */
private val SYMBOLS = mapOf(
    "files" to "folder",
    "photos" to "photo.on.rectangle",
    "music" to "music.note",
    "videos" to "film",
)
