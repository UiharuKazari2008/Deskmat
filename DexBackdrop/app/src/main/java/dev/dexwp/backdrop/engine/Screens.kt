package dev.dexwp.backdrop.engine

import android.content.Context
import android.content.pm.PackageManager
import android.content.ComponentName
import android.hardware.display.DisplayManager
import android.provider.Settings
import dev.dexwp.backdrop.data.Library
import android.view.Display
import dev.dexwp.backdrop.model.Screen

/** The phone's own screens: which exist, and their portrait shape for crops. */
object Screens {
    /** Hidden DisplayManager category that also lists the folded-away panel. */
    private const val ALL_INCLUDING_DISABLED = "android.hardware.display.category.ALL_INCLUDING_DISABLED"
    private const val TYPE_INTERNAL = 1

    private const val LAUNCHER = "com.sec.android.app.launcher"
    private var dex: Boolean? = null

    fun foldable(c: Context) = c.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE)

    /**
     * Samsung DeX has no feature flag. It counts as supported when DeX has been seen running,
     * when One UI keeps its DeX settings, or when the launcher ships its enabled DeX service
     * (the "DeX" quick settings tile). Phones like the Galaxy A52 have none of these.
     */
    fun dexSupported(c: Context): Boolean {
        dex?.let { return it }
        val s = Library.get(c)
        val supported = s.dexActive || s.wallpapers.any { it.target.isDex } ||
            runCatching { Settings.System.getString(c.contentResolver, "dex_on_external_display") != null }.getOrDefault(false) ||
            runCatching { c.packageManager.getServiceInfo(ComponentName(LAUNCHER, "com.honeyspace.dexservice.DesktopModeTile"), 0); true }.getOrDefault(false)
        return supported.also { dex = it }
    }

    /**
     * True when a foldable is folded: its built-in display is then the narrow cover panel
     * (short/long side under 0.6; the main panel is close to square).
     */
    fun isFolded(c: Context): Boolean {
        if (!foldable(c)) return false
        val m = c.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.mode ?: return false
        return minOf(m.physicalWidth, m.physicalHeight).toFloat() / maxOf(m.physicalWidth, m.physicalHeight) < 0.6f
    }

    /** The screens this phone has, in switcher order (cover, main, DeX). */
    fun available(c: Context): List<Screen> = Screen.entries.filter {
        when (it) {
            Screen.COVER -> foldable(c)
            Screen.MAIN -> true
            Screen.DESKTOP -> dexSupported(c)
        }
    }

    /**
     * Portrait aspect (short/long side) of the main or cover panel. On a foldable both built-in
     * panels are listed (one disabled); the cover is the narrower one. Falls back to Galaxy Z
     * Fold shapes when the panels can't be read.
     */
    fun aspect(c: Context, screen: Screen): Float = size(c, screen).let { (short, long) -> short.toFloat() / long }

    /**
     * (short side, long side) in pixels of the main or cover panel. The cover is the narrower
     * panel. Falls back to Galaxy Z Fold sizes when the panels can't be read.
     */
    fun size(c: Context, screen: Screen): Pair<Int, Int> {
        val panels = runCatching {
            val dm = c.getSystemService(DisplayManager::class.java)
            val type = Display::class.java.getMethod("getType")
            dm.getDisplays(ALL_INCLUDING_DISABLED).filter { type.invoke(it) == TYPE_INTERNAL }
                .map { d -> d.mode.let { minOf(it.physicalWidth, it.physicalHeight) to maxOf(it.physicalWidth, it.physicalHeight) } }
                .distinct()
        }.getOrDefault(emptyList())
        val ratio = { p: Pair<Int, Int> -> p.first.toFloat() / p.second }
        return when {
            panels.isEmpty() -> if (screen == Screen.COVER) 1080 to 2520 else 1968 to 2184
            screen == Screen.COVER -> panels.minBy(ratio)
            else -> panels.maxBy(ratio)
        }
    }
}
