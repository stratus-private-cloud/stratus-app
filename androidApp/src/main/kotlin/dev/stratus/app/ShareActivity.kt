package dev.stratus.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.stratus.core.appContainer
import dev.stratus.core.backup.AndroidPickedFiles
import dev.stratus.core.backup.BackupWorker
import dev.stratus.core.backup.ShareOutcome
import dev.stratus.core.backup.ShareTargets
import kotlinx.coroutines.launch

/**
 * Stratus in the system's share sheet (stratus-app#128): what is shared is
 * copied, queued as a hand upload into the shares' folder and sent by the
 * same worker a tap starts. Nothing is drawn but a line saying where it went,
 * and whoever shared is left in the app they shared from.
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Once: a recreated activity would queue the same share twice.
        if (savedInstanceState != null) return
        val uris = streamsOf(intent)
        lifecycleScope.launch {
            val files = AndroidPickedFiles(applicationContext).keep(uris)
            val into = ShareTargets.folderOf(intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID))
            val outcome = appContainer(applicationContext).backup.share(files, into)
            if (outcome is ShareOutcome.Queued) BackupWorker.now(applicationContext)
            Toast.makeText(applicationContext, said(outcome), Toast.LENGTH_LONG).show()
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private fun said(outcome: ShareOutcome): String = when (outcome) {
        is ShareOutcome.Queued ->
            (if (outcome.count == 1) "Uploading to " else "Uploading ${outcome.count} files to ") + outcome.folder
        ShareOutcome.SignedOut -> "Sign in to a server in Stratus first"
        ShareOutcome.NotAFile -> "Stratus uploads files, not text or links"
    }

    @Suppress("DEPRECATION")
    private fun streamsOf(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            },
        )
        Intent.ACTION_SEND_MULTIPLE -> if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }.orEmpty()
        else -> emptyList()
    }
}
