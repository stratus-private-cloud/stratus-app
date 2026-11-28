package dev.stratus.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.view.View
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.stratus.core.appContainer
import dev.stratus.core.backup.BackupWorker
import dev.stratus.core.backup.backupTile
import dev.stratus.core.media.Thumbnail
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The home screen widget (stratus-app#266): the backup's state over today's
 * photograph from an earlier year, or over the sky where there is none.
 *
 * Redrawn from what is kept on the phone whenever a pass ends; the photograph
 * is fetched once a day by [StratusWidgetWorker], since it is a dozen
 * listings and a thumbnail and a broadcast has seconds.
 */
class StratusWidget : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == BackupWorker.PASSED) redraw(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = redraw(context)

    private fun redraw(context: Context) {
        val app = context.applicationContext
        if (placed(app).isEmpty()) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                draw(app)
                if (TodaysMemory(app).stale()) {
                    WorkManager.getInstance(app).enqueueUniqueWork(
                        WORK, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<StratusWidgetWorker>().build(),
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val WORK = "stratus-widget-memory"

        private fun placed(context: Context): IntArray =
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, StratusWidget::class.java))

        suspend fun draw(context: Context) {
            val ids = placed(context)
            if (ids.isEmpty()) return
            val backup = appContainer(context).backup
            val status = backupTile(backup.enabled()?.let { backup.status.of() }).subtitle
            val memory = TodaysMemory(context).kept()

            val views = RemoteViews(context.packageName, R.layout.widget_backup)
            views.setTextViewText(R.id.widget_status, "Backup · $status")
            val picture = memory?.let { BitmapFactory.decodeFile(it.second.path) }
            if (memory != null && picture != null) {
                views.setImageViewBitmap(R.id.widget_photo, picture)
                views.setTextViewText(R.id.widget_caption, memory.first)
                views.setViewVisibility(R.id.widget_photo, View.VISIBLE)
                views.setViewVisibility(R.id.widget_caption, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_photo, View.GONE)
                views.setViewVisibility(R.id.widget_caption, View.GONE)
            }
            val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE),
            )
            AppWidgetManager.getInstance(context).updateAppWidget(ids, views)
        }
    }
}

/** Fetches today's photograph and its picture, keeps them, and redraws. */
class StratusWidgetWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val kept = TodaysMemory(applicationContext)
        kept.tried()
        try {
            val today = LocalDate.now()
            val container = appContainer(applicationContext)
            // Signed out is not a day without a photograph, which would last until tomorrow.
            if (container.current() == null) return Result.success()
            val memory = container.memory(today.year, today.monthValue, today.dayOfMonth)
            when (val picture = memory?.let { container.thumbnails.of(it.photo, PIXELS) }) {
                is Thumbnail.Picture -> kept.keep(memory.caption, picture.bytes)
                // A refusal says nothing about the photograph: tried again later.
                is Thumbnail.NotNow -> Unit
                Thumbnail.None, null -> kept.keep(null, null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Nothing learned about whether there is a photograph today, so
            // nothing is kept and the next redraw after the wait asks again.
        }
        StratusWidget.draw(applicationContext)
        return Result.success()
    }

    private companion object {
        const val PIXELS = 300
    }
}

/**
 * What the widget shows of today, kept between redraws: a caption and a JPEG
 * in the app's files, or a record that today has none.
 */
private class TodaysMemory(context: Context) {
    private val prefs = context.getSharedPreferences("widget", Context.MODE_PRIVATE)
    private val file = File(context.filesDir, "widget-memory.jpg")
    private val today get() = LocalDate.now().toString()

    fun kept(): Pair<String, File>? {
        if (prefs.getString(DAY, null) != today) return null
        val caption = prefs.getString(CAPTION, null) ?: return null
        return if (file.exists()) caption to file else null
    }

    /**
     * Whether today has not been settled yet and the last try was long enough
     * ago. The wait is not politeness: WorkManager switching its own receivers
     * on and off is a package change, which the launcher answers with an
     * update, so a failing fetch asked for on every update would loop.
     */
    fun stale(): Boolean =
        prefs.getString(DAY, null) != today && System.currentTimeMillis() - prefs.getLong(TRIED, 0) > RETRY_MS

    fun tried() = prefs.edit().putLong(TRIED, System.currentTimeMillis()).apply()

    fun keep(caption: String?, bytes: ByteArray?) {
        if (bytes != null) file.writeBytes(bytes) else file.delete()
        prefs.edit().putString(DAY, today).putString(CAPTION, caption).apply()
    }

    private companion object {
        const val DAY = "day"
        const val CAPTION = "caption"
        const val TRIED = "tried"
        const val RETRY_MS = 60 * 60 * 1000L
    }
}
