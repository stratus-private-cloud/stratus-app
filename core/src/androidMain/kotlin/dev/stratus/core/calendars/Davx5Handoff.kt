package dev.stratus.core.calendars

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * DAVx5's login, opened on the server with the user and the password filled
 * in -- the extras its `LoginActivity` reads -- or its store page when it is
 * not installed.
 */
class Davx5Handoff(private val context: Context) : CalendarHandoff {

    override val toDavx5 = true

    override suspend fun open(setup: CalendarSetup) {
        if (setup !is CalendarSetup.Davx5) return
        val login = Intent(Intent.ACTION_VIEW, Uri.parse(setup.uri))
            // The package and not just the scheme: anything can claim
            // davx5://, and this intent carries the password.
            .setPackage(PACKAGE)
            .putExtra("username", setup.credentials.username)
            .putExtra("password", setup.credentials.password)
        if (!start(login)) {
            start(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE"))) ||
                start(Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD)))
        }
    }

    // NEW_TASK because this starts from an application context, as the file
    // handoff does.
    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    private companion object {
        const val PACKAGE = "at.bitfire.davdroid"
        const val DOWNLOAD = "https://www.davx5.com/download"
    }
}
