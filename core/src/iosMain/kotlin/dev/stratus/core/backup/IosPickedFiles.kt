package dev.stratus.core.backup

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * Files picked with the system's document picker, **copied into the app's own
 * container** before anything is queued (stratus-app#127).
 *
 * A security-scoped URL into somebody's iCloud Drive does not survive a
 * relaunch, and a hand upload resumes like any other row -- possibly after the
 * system has killed the app and relaunched it to report. So the picker is
 * asked for copies (`asCopy`), and each is moved under Application Support,
 * where nothing is purged behind a pass's back. The id is the copy's path
 * relative to that folder, because the container's own path is not stable
 * across an update.
 *
 * Presented from Kotlin, as the share sheet is: the Swift side stays the
 * bridge and nothing else.
 */
@OptIn(ExperimentalForeignApi::class)
class IosPickedFiles : PickedFiles {

    // UIKit holds a picker's delegate weakly, so somebody has to hold it strongly.
    private var holding: NSObject? = null

    /** What somebody picked, copied and described; empty if they cancelled. */
    suspend fun pick(): List<PickedFile> {
        val urls = suspendCancellableCoroutine { waiting ->
            val top = topmost()
            if (top == null) {
                waiting.resume(emptyList())
                return@suspendCancellableCoroutine
            }
            val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeItem), asCopy = true)
            picker.allowsMultipleSelection = true
            val delegate = Picked { picked ->
                holding = null
                if (waiting.isActive) waiting.resume(picked)
            }
            holding = delegate
            picker.delegate = delegate
            top.presentViewController(picker, animated = true, completion = null)
        }
        return withContext(Dispatchers.Default) { urls.mapNotNull(::keep) }
    }

    private fun keep(url: NSURL): PickedFile? {
        val from = url.path ?: return null
        val name = url.lastPathComponent ?: return null
        val id = NSUUID().UUIDString() + "/" + name
        val to = Path(folder(), id)
        SystemFileSystem.createDirectories(to.parent!!)
        try {
            SystemFileSystem.atomicMove(Path(from), to)
        } catch (_: Exception) {
            // Moved where the picker's copy is ours to move, and copied where
            // it is not.
            SystemFileSystem.source(Path(from)).buffered().use { source ->
                SystemFileSystem.sink(to).buffered().use { sink -> sink.transferFrom(source) }
            }
        }
        val size = SystemFileSystem.metadataOrNull(to)?.size ?: return null
        val type = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() }
            ?.let { UTType.typeWithFilenameExtension(it)?.preferredMIMEType }
        return PickedFile(id = id, name = name, size = size, contentType = type)
    }

    override suspend fun open(id: String, from: Long): RawSource = withContext(Dispatchers.Default) {
        val source = SystemFileSystem.source(Path(folder(), id)).buffered()
        source.skip(from)
        source
    }

    override suspend fun forget(id: String) {
        withContext(Dispatchers.Default) {
            val copy = Path(folder(), id)
            SystemFileSystem.delete(copy, mustExist = false)
            copy.parent?.let { SystemFileSystem.delete(it, mustExist = false) }
        }
    }

    private fun folder(): String {
        val support = NSFileManager.defaultManager
            .URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask)
            .firstOrNull() as? NSURL
        return (support?.path ?: "") + "/Stratus/picked"
    }

    private fun topmost(): UIViewController? {
        val window = UIApplication.sharedApplication.connectedScenes
            .filterIsInstance<UIWindowScene>()
            .flatMap { scene -> scene.windows.filterIsInstance<UIWindow>() }
            .firstOrNull { it.isKeyWindow() }
        var top = window?.rootViewController
        while (top?.presentedViewController != null) top = top.presentedViewController
        return top
    }

    private class Picked(private val done: (List<NSURL>) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            done(didPickDocumentsAtURLs.filterIsInstance<NSURL>())
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
            done(emptyList())
        }
    }
}
