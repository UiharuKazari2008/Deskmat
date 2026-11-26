package dev.dexwp.backdrop.system

import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import dev.dexwp.backdrop.data.Library
import android.util.Log
import dev.dexwp.backdrop.engine.Engine
import dev.dexwp.backdrop.engine.Images
import dev.dexwp.backdrop.model.Group
import dev.dexwp.backdrop.model.Wallpaper

/**
 * App shortcuts, which Modes and Routines can launch ("Open an app or do an app action"):
 * "automatic", on/off for each flag marked for shortcuts, then one per group and wallpaper with a command name.
 */
object Shortcuts {
    fun publish(c: Context) {
        val sm = c.getSystemService(ShortcutManager::class.java)
        val list = mutableListOf(
            ShortcutInfo.Builder(c, "auto")
                .setShortLabel("DeX: automatic").setLongLabel("Resume wallpaper triggers")
                .setIcon(Icon.createWithResource(c, android.R.drawable.ic_menu_rotate))
                .setIntent(CommandReceiver.intent(c, "clear")).build(),
        )
        val s = Library.get(c)
        for (flag in s.shortcutFlags.sorted()) {
            for (on in listOf(true, false)) {
                val state = if (on) "on" else "off"
                list += ShortcutInfo.Builder(c, "flag_${state}_$flag")
                    .setShortLabel("$flag $state")
                    .setLongLabel("DeX flag “$flag” $state")
                    .setIcon(Icon.createWithResource(c, if (on) android.R.drawable.checkbox_on_background else android.R.drawable.checkbox_off_background))
                    .setIntent(CommandReceiver.intent(c, "flag", flag, state = state)).build()
            }
        }
        s.groups.filter { !it.command.isNullOrBlank() }.forEach { list += build(c, it) }
        s.wallpapers.filter { !it.command.isNullOrBlank() }.forEach { list += build(c, it) }
        val max = sm.maxShortcutCountPerActivity
        if (list.size > max) Log.w(Engine.TAG, "shortcuts: ${list.size} wanted, launcher allows $max; dropping the rest")
        sm.dynamicShortcuts = list.take(max)
    }

    fun requestPin(c: Context, wp: Wallpaper): Boolean {
        val sm = c.getSystemService(ShortcutManager::class.java)
        return sm.isRequestPinShortcutSupported && sm.requestPinShortcut(build(c, wp), null)
    }

    fun requestPin(c: Context, g: Group): Boolean {
        val sm = c.getSystemService(ShortcutManager::class.java)
        return sm.isRequestPinShortcutSupported && sm.requestPinShortcut(build(c, g), null)
    }

    /** Makes the group active (until a trigger changes); the icon is its first image. */
    private fun build(c: Context, g: Group): ShortcutInfo {
        val first = Library.get(c).inGroup(g).firstOrNull()
        val icon = first?.let { Images.preview(it.file, it.crop, 192) }?.let { Icon.createWithBitmap(it) }
            ?: Icon.createWithResource(c, android.R.drawable.ic_menu_gallery)
        return ShortcutInfo.Builder(c, "group_${g.id}")
            .setShortLabel("DeX: ${g.command}")
            .setLongLabel("${g.target.label}: ${g.name}")
            .setIcon(icon)
            .setIntent(CommandReceiver.intent(c, "set", g.command, g.target))
            .build()
    }

    private fun build(c: Context, wp: Wallpaper): ShortcutInfo {
        val icon = Images.preview(wp.file, wp.crop, 192)?.let { Icon.createWithBitmap(it) }
            ?: Icon.createWithResource(c, android.R.drawable.ic_menu_gallery)
        return ShortcutInfo.Builder(c, "wp_${wp.id}")
            .setShortLabel("DeX: ${wp.command}")
            .setLongLabel("${wp.target.label}: ${wp.name}")
            .setIcon(icon)
            .setIntent(CommandReceiver.intent(c, "set", wp.command, wp.target))
            .build()
    }
}
