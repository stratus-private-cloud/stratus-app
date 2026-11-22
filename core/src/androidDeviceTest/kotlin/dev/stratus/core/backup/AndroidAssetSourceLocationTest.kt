package dev.stratus.core.backup

import android.Manifest
import android.graphics.Bitmap
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.readByteArray

/**
 * A photograph keeps its place on the way out (stratus-app#228).
 *
 * The file has to belong to somebody else, because Android only redacts what
 * the reading app did not write -- `AndroidAssetSourceTest`'s seed is ours, so
 * its location would survive with or without the fix. The shell writes this one.
 *
 * What it cannot show is the redaction itself: the permission is granted to
 * the process before the first test, and on API 34 it lifts the redaction from
 * a plain read too. Taking it away again kills the process.
 */
class AndroidAssetSourceLocationTest {

    @get:org.junit.Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.ACCESS_MEDIA_LOCATION,
    )

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val source = AndroidAssetSource(context)

    private val folder = "StratusLocated${Random.nextInt(100_000)}"
    private val directory = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        folder,
    )
    private val photograph = File(directory, "located.jpg")

    @BeforeTest
    fun aPhotographTakenInLisbonBySomebodyElse() = runTest {
        val made = File(context.cacheDir, "located.jpg")
        made.outputStream().use {
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
        ExifInterface(made.path).apply {
            // The platform's ExifInterface has no setLatLong; that is androidx's.
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "38/1,43/1,2028/100")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "9/1,8/1,2148/100")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
            saveAttributes()
        }

        shell("mkdir -p ${directory.path}")
        val (out, into) = instrumentation.uiAutomation.executeShellCommandRw("tee ${photograph.path}")
        ParcelFileDescriptor.AutoCloseOutputStream(into).use { it.write(made.readBytes()) }
        ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes() }
        made.delete()
        scan()
    }

    @AfterTest
    fun tidyUp() = runTest {
        shell("rm -r ${directory.path}")
        scan()
    }

    @Test
    fun theBytesSentStillSayWhereItWasTaken() = runTest {
        val asset = located()
        val sent = source.open(asset.localId, AssetPart.Still).buffered().readByteArray()
        val place = placeOf(sent)
        assertTrue(place != null && near(place), "sent without its place: ${place?.toList()}")
    }

    @Test
    fun asksForLocationsWithTheLibrary() {
        assertTrue(Manifest.permission.ACCESS_MEDIA_LOCATION in source.permissions())
    }

    private suspend fun located(): Asset {
        val bucket = source.sources().single { it.label == folder }.id
        return source.assets(setOf(bucket)).single()
    }

    private fun placeOf(bytes: ByteArray): FloatArray? {
        val place = FloatArray(2)
        return place.takeIf { ExifInterface(ByteArrayInputStream(bytes)).getLatLong(it) }
    }

    private fun near(place: FloatArray) =
        abs(place[0] - LISBON_LAT) < 0.001 && abs(place[1] - LISBON_LON) < 0.001

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes() }
    }

    private suspend fun scan() = suspendCancellableCoroutine { done ->
        MediaScannerConnection.scanFile(context, arrayOf(photograph.path), arrayOf("image/jpeg")) { _, _ ->
            done.resume(Unit)
        }
    }

    private companion object {
        const val LISBON_LAT = 38.7223
        const val LISBON_LON = -9.1393
    }
}
