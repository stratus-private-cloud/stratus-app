package dev.stratus.core.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.stratus.core.appContainer
import java.util.concurrent.TimeUnit

/**
 * When the backup runs, and how it stays alive while it does.
 *
 * **It executes and decides nothing.** What goes next, what is retried and when
 * to give up are [UploadQueue]'s, and they are there because this is the half
 * that runs where no test can watch it. What is decided here is only how to
 * survive Android: a foreground service for the duration where Android allows
 * one, because background work that is not in the foreground is killed by OEM
 * battery managers however correct it is, and stopping between files when the
 * system asks.
 */
class BackupWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        // The trigger does nothing but re-arm and hand over (stratus-app#232).
        // Re-arming after the pass meant a trigger that fired while signed out,
        // or a pass that threw, ended the chain until the app was opened; a
        // retry kept the content constraint and so waited for another photograph
        // as well as the backoff; and the next trigger sat blocked behind a long
        // pass, so nothing watched the roll while one ran. The pass itself goes
        // on the same chain a tap does, where a second one is run by the first.
        if (NEW_MEDIA in tags) {
            watchForNewMedia(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
            enqueueOnce(applicationContext, connected)
            return Result.success()
        }

        val backup = appContainer(applicationContext).backup
        if (backup.enabled() == null) return Result.success()

        val foreground = intoTheForeground()

        val outcome = backup.pass(
            // Asked between files, so being reclaimed costs nothing: the queue
            // is in the database and picks up where it stopped.
            keepGoing = { !isStopped },
            onStep = { step ->
                if (foreground && step is QueueStep.Uploaded) {
                    setForegroundAsync(notify(step.path.substringAfterLast('/')))
                }
            },
        )

        // Coming back later is WorkManager's job; whether there is a reason to
        // is the queue's, and which item is ready is the queue's too. The two
        // backoffs are about different things and do not fight.
        return when (outcome) {
            PassOutcome.ComeBackLater -> Result.retry()
            // Not a retry, which would come back to the same metered
            // connection and park again, spending the backoff on nothing. A
            // constraint is the thing that waits for wifi (stratus-app#133).
            PassOutcome.WhenThereIsWifi -> {
                whenThereIsWifi(applicationContext)
                Result.success()
            }
            PassOutcome.Finished -> Result.success()
        }
    }

    /**
     * Whether the pass runs as a foreground service, which outlives a battery
     * manager; without one it still has the ten minutes any job gets, and the
     * queue picks up where it stopped.
     *
     * Android 12 refuses a foreground service started from the background,
     * which is every pass nobody tapped for -- and it refuses by throwing. Not
     * catching it was a backup that only ever ran with the app open, and that
     * nobody could see fail: WorkManager keeps the exception to itself.
     */
    internal suspend fun intoTheForeground(): Boolean = try {
        setForeground(getForegroundInfo())
        true
    } catch (refused: IllegalStateException) {
        Log.i(LOG, "running without a foreground service: ${refused.message}")
        false
    }

    /**
     * Overridden so the notification -- and the channel it needs, which throws
     * on modern Android if it is missing -- can be built by a test without
     * running a backup.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo = notify("Looking for new photographs")

    private fun notify(detail: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Backup", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setContentTitle("Backing up")
            .setContentText(detail)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION, notification)
        }
    }

    companion object {
        private const val CHANNEL = "backup"
        private const val NOTIFICATION = 1
        private const val PERIODIC = "stratus-backup"
        /** Public for the end-to-end suite, which waits on the pass it asked for. */
        const val ONCE = "stratus-backup-now"
        private const val ON_WIFI = "stratus-backup-on-wifi"
        /** Internal for the device test, which looks for it re-armed. */
        internal const val NEW_MEDIA = "stratus-backup-on-new-media"
        private const val LOG = "StratusBackup"

        /**
         * Asks Android to come back regularly.
         *
         * Six hours rather than the fifteen-minute floor: this is a backup and
         * not a chat client, and a phone that wakes an app four times an hour is
         * a phone whose owner turns the app off.
         */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BackupWorker>(6, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                    )
                    .build(),
            )
            watchForNewMedia(context, ExistingWorkPolicy.KEEP)
        }

        /**
         * Runs a pass when the camera roll changes, so a photograph does not
         * wait for the six hours.
         *
         * A content trigger fires once, so the work it starts arms the next
         * one, appended behind itself: replacing would cancel the very work
         * doing the arming. Opening the app keeps whatever is armed.
         */
        internal fun watchForNewMedia(context: Context, policy: ExistingWorkPolicy) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NEW_MEDIA,
                policy,
                OneTimeWorkRequestBuilder<BackupWorker>()
                    .addTag(NEW_MEDIA)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                            // A burst of photographs is one pass, not one each.
                            .setTriggerContentUpdateDelay(1, TimeUnit.MINUTES)
                            .build(),
                    )
                    .build(),
            )
        }

        /**
         * Comes back the moment the phone is on a connection nobody pays for.
         *
         * `REPLACE` rather than `KEEP`: the request is spent once it runs, and
         * one left over from a previous park would otherwise be kept in place
         * of this one for ever.
         */
        fun whenThereIsWifi(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ON_WIFI,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<BackupWorker>()
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build(),
                    )
                    .build(),
            )
        }

        /**
         * Runs a pass now, for somebody who does not want to wait six hours.
         *
         * Appended rather than kept: with `KEEP` a tap during a pass was
         * dropped without a word, and that pass had already looked at the
         * camera roll -- so the photograph just taken, which is why anybody
         * taps, waited six hours (stratus-app#188). A pass with nothing new is
         * a listing of the roll and no request, so a few taps cost nothing.
         */
        fun now(context: Context) = enqueueOnce(context, Constraints.Builder().build())

        private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        private fun enqueueOnce(context: Context, constraints: Constraints) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONCE,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(constraints).build(),
            )
        }
    }
}
