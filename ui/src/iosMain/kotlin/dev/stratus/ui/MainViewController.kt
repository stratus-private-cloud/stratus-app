package dev.stratus.ui

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.ComposeUIViewController
import dev.stratus.core.backup.IosAssetSource
import dev.stratus.core.backup.IosPickedFiles
import dev.stratus.core.session.Ask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import dev.stratus.core.hostAppContainer
import kotlinx.coroutines.runBlocking

@Suppress("unused", "FunctionName") // Called from Swift, not from Kotlin.
fun MainViewController() = ComposeUIViewController {
    val scope = rememberCoroutineScope { Dispatchers.Main.immediate }
    App(
        container,
        // A pass now, for somebody who does not want to wait for the system to
        // feel like it. What it starts still leaves through the background
        // session, so closing the app does not stop what it handed over
        // (stratus-app#20).
        onBackUpNow = { scope.launch { container.backup.pass() } },
        // Asking is the shell's to trigger and the platform's to perform, as
        // on Android. Without it `access()` answers None for ever and the
        // backup sits in front of a camera roll nobody ever offered it
        // (stratus-app#20).
        //
        // Notifications are not asked for here: iOS has nothing to show and
        // the backup is the system's to schedule, so a prompt would be asking
        // for permission to say nothing.
        onAsk = { asks ->
            if (Ask.Photos in asks) {
                scope.launch { (container.assets as? IosAssetSource)?.request() }
            }
        },
        shortcuts = iosShortcuts,
        // Picked, copied into the container and queued, and then the same
        // pass a tap starts, which hands it to the background session
        // (stratus-app#127).
        onUpload = { folder ->
            scope.launch {
                val files = (container.picked as? IosPickedFiles)?.pick().orEmpty()
                if (files.isEmpty()) return@launch
                container.backup.uploadByHand(folder, files)
                container.backup.pass()
            }
        },
    )
}

private val container by lazy {
    hostAppContainer().also {
        // Before the first screen, so a crash drawing it is reported too. A
        // keychain read, which is what blocking here costs.
        if (runBlocking { it.reporting.granted() }) CrashReports.start()
    }
}

/**
 * Opening the app sends what the Share Extension left behind (stratus-app#128),
 * from Swift each time a scene becomes active: the extension cannot start a
 * pass, so this is the first moment one can. Only when a share is waiting,
 * since a pass also looks at the camera roll.
 */
@Suppress("unused") // Called from Swift.
fun takeInShares() {
    if ((container.picked as? IosPickedFiles)?.hasShares() != true) return
    MainScope().launch { container.backup.pass() }
}
