package dev.stratus.core.backup

import android.Manifest
import android.content.ContentUris
import android.graphics.Bitmap
import android.media.ExifInterface
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
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
import kotlin.test.assertFalse
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
            setLatLong(LISBON_LAT, LISBON_LON)
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
        val place = ExifInterface(ByteArrayInputStream(sent)).latLong
        assertTrue(place != null && near(place), "sent without its place: ${place?.toList()}")
    }

    // Without this the test above could pass on an image Android never
    // redacted, and prove nothing.
    @Test
    fun readPlainlyTheSamePhotographHasNoPlace() = runTest {
        val asset = located()
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val uri = ContentUris.withAppendedId(files, asset.localId.toLong())
        val plain = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val place = ExifInterface(ByteArrayInputStream(plain)).latLong
        assertFalse(place != null && near(place), "Android did not redact it, so the other test proves nothing")
    }

    @Test
    fun asksForLocationsWithTheLibrary() {
        assertTrue(Manifest.permission.ACCESS_MEDIA_LOCATION in source.permissions())
    }

    private suspend fun located(): Asset {
        val bucket = source.sources().single { it.label == folder }.id
        return source.assets(setOf(bucket)).single()
    }

    private fun near(place: DoubleArray) =
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
