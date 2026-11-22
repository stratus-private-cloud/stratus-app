package dev.stratus.core.backup

import android.Manifest
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The plumbing, which is all that needed a device.
 *
 * What a pass over the camera roll does is `BackupRunTest`, on the JVM, with
 * fakes. What cannot be checked there is whether Android will let this build a
 * notification channel and call itself a foreground service of the right type --
 * and getting that wrong is a crash on somebody's phone rather than a failure.
 */
class BackupWorkerTest {

    @get:org.junit.Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun worker(): BackupWorker =
        TestListenableWorkerBuilder<BackupWorker>(context).build()

    @Test
    fun buildsTheNotificationAndroidDemandsBeforeItWillRunInTheForeground() = runTest {
        val info = worker().getForegroundInfo()

        assertTrue(info.notification.channelId.isNotEmpty(), "no channel, which throws on API 26 and up")
        if (Build.VERSION.SDK_INT >= 29) {
            // Android 14 refuses a foreground service that does not say what kind
            // it is, and Play reviews the answer.
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
        }
    }

    @Test
    fun aRefusedForegroundIsAPassWithoutOne() = runTest {
        // What Android 12 does to a foreground service started from the
        // background, which is every pass nobody tapped for.
        val refused = TestListenableWorkerBuilder<BackupWorker>(context)
            .setForegroundUpdater { _, _, _ -> throw IllegalStateException("not from the background") }
            .build()
        assertFalse(refused.intoTheForeground())
    }

    @Test
    fun aTriggerReArmsItselfAndHandsThePassOnEvenSignedOut() = runTest {
        // stratus-app#232: nobody is signed in here, which used to end the
        // chain -- the trigger returned before it re-armed.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        val trigger = TestListenableWorkerBuilder<BackupWorker>(context)
            .setTags(listOf(BackupWorker.NEW_MEDIA))
            .build()

        assertEquals(ListenableWorker.Result.success(), trigger.doWork())

        val work = WorkManager.getInstance(context)
        val armed = work.getWorkInfosForUniqueWork(BackupWorker.NEW_MEDIA).get()
        assertTrue(armed.any { it.state == WorkInfo.State.ENQUEUED }, "not re-armed: $armed")
        val pass = work.getWorkInfosForUniqueWork(BackupWorker.ONCE).get()
        assertTrue(pass.any { it.state == WorkInfo.State.ENQUEUED }, "no pass handed on: $pass")
    }

    @Test
    fun finishesQuietlyWhenNoInstanceWantsBackingUp() = runTest {
        // Nobody has signed in on this emulator, so there is nothing enabled --
        // and the right answer to that is success rather than a retry loop.
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
    }
}
