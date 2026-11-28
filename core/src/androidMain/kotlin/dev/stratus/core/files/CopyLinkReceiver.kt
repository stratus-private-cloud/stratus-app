package dev.stratus.core.files

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle

/** The sheet's "Copy link" (stratus-app#268); Android says it was copied. */
class CopyLinkReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val link = intent.getStringExtra(LINK) ?: return
        val clip = ClipData.newPlainText(intent.getStringExtra(NAME) ?: "Link", link)
        // A week's access to the file, so it is kept out of the clipboard's preview.
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    companion object {
        const val LINK = "link"
        const val NAME = "name"
    }
}
