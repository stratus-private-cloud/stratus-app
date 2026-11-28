package dev.stratus.core.backup

import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build

/**
 * The recent folders as sharing shortcuts (stratus-app#269): the row of
 * destinations at the top of the share sheet, where a chat app puts its
 * recent conversations. The share target they belong to is declared in the
 * app's `shortcuts.xml` under [CATEGORY], and a tap reaches `ShareActivity`
 * with the shortcut's id as `EXTRA_SHORTCUT_ID`.
 *
 * From Android 13 only, because before it a dynamic shortcut cannot be kept
 * off the launcher icon's menu, which holds a Stratus's parts.
 */
object ShareTargets {
    const val CATEGORY = "dev.stratus.category.FOLDER"
    private const val PREFIX = "folder:"

    fun folderOf(shortcutId: String?): String? = shortcutId?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)

    fun publish(context: Context, folders: List<String>) {
        if (Build.VERSION.SDK_INT < 33) return
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val ids = folders.map { PREFIX + it }
        val gone = manager.dynamicShortcuts.map { it.id }.filter { it.startsWith(PREFIX) && it !in ids }
        if (gone.isNotEmpty()) {
            manager.removeLongLivedShortcuts(gone)
            manager.removeDynamicShortcuts(gone)
        }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        folders.forEachIndexed { rank, folder ->
            manager.pushDynamicShortcut(
                ShortcutInfo.Builder(context, PREFIX + folder)
                    .setShortLabel(folder.substringAfterLast('/'))
                    .setLongLabel(folder)
                    .setIcon(Icon.createWithResource(context, context.applicationInfo.icon))
                    .setCategories(setOf(CATEGORY))
                    .setLongLived(true)
                    .setRank(rank)
                    .setExcludedFromSurfaces(ShortcutInfo.SURFACE_LAUNCHER)
                    .setIntent(open)
                    .build(),
            )
        }
    }
}
