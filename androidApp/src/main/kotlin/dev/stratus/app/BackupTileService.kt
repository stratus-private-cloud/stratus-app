package dev.stratus.app

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.stratus.core.appContainer
import dev.stratus.core.backup.BackupTile
import dev.stratus.core.backup.BackupWorker
import dev.stratus.core.backup.backupTile
import dev.stratus.core.backup.startingTile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The backup in the notification shade (stratus-app#265): what the strip says,
 * and a tap that starts a pass. What it says and what a tap does are
 * [backupTile]'s; this holds the tile and nothing else.
 */
class BackupTileService : TileService() {

    private var scope: CoroutineScope? = null
    private var shown: BackupTile? = null
    private var tappedAt = 0L

    // Asked again while the shade is open and never otherwise: the status is
    // a query rather than a flow, and a tile nobody is looking at needs none.
    override fun onStartListening() {
        scope = MainScope().also {
            it.launch {
                while (isActive) {
                    refresh()
                    delay(REFRESH_MS)
                }
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
    }

    override fun onClick() {
        when (shown?.tap) {
            BackupTile.Tap.BackUpNow -> {
                BackupWorker.now(applicationContext)
                tappedAt = SystemClock.elapsedRealtime()
                show(startingTile)
            }
            BackupTile.Tap.OpenTheApp, null -> if (isLocked) unlockAndRun(::openTheApp) else openTheApp()
        }
    }

    private suspend fun refresh() {
        val backup = appContainer(applicationContext).backup
        val tile = backupTile(backup.enabled()?.let { backup.status.of() })
        // WorkManager takes a moment to start the pass a tap asked for, and
        // until it does the status still says what it said before the tap.
        val starting = SystemClock.elapsedRealtime() - tappedAt < GRACE_MS
        if (starting && tile.look != BackupTile.Look.Active) return
        show(tile)
    }

    private fun show(tile: BackupTile) {
        shown = tile
        val qs = qsTile ?: return
        qs.icon = Icon.createWithResource(this, R.drawable.ic_stratus_root)
        qs.label = "Stratus"
        qs.subtitle = tile.subtitle
        qs.state = when (tile.look) {
            BackupTile.Look.Active -> Tile.STATE_ACTIVE
            BackupTile.Look.Inactive -> Tile.STATE_INACTIVE
        }
        qs.updateTile()
    }

    private fun openTheApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        const val REFRESH_MS = 2_000L
        const val GRACE_MS = 6_000L
    }
}
