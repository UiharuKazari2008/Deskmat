package dev.dexwp.backdrop.engine

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.ParcelFileDescriptor
import android.view.Display
import dev.dexwp.backdrop.data.Library
import dev.dexwp.backdrop.model.Crop
import dev.dexwp.backdrop.model.Screen
import dev.dexwp.backdrop.model.Target
import dev.dexwp.backdrop.model.Wallpaper
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import android.graphics.Point
import android.os.Build
import android.util.Log
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Writes a slot the way Wallpaper & Style does: the full original plus a crop hint.
 * system_server keeps the original, cuts it at the hint and downsamples if needed.
 */
object Applier {
    /** Writes [image] to slot [t] with that slot's crop (another slot's crop when shown through a linked group). */
    fun apply(c: Context, image: Wallpaper, t: Target = image.target) {
        val wp = image.on(t)
        val crop = wp.crop ?: centreCrop(wp.width, wp.height, aspect(c, wp.target))
        val wm = WallpaperManager.getInstance(c)
        val which = which(c, wp.target)
        if (rotates(c, wp.target)) return applyRotating(c, wm, wp, crop, which)
        val target = downsampleWidth(c, wp.target)
        if (target != null && crop.width > target) {
            // Pre-scaled on the phone with high-quality filtering; Android then applies it 1:1.
            val file = Downsampler.render(c, wp, crop, target)
            FileInputStream(file).use { wm.setStream(it, null, true, which) }
        } else {
            FileInputStream(wp.file).use { wm.setStream(it, crop.toRect(), true, which) }
        }
    }

    /** Phone slots the user turned rotation on for (DeX never rotates). */
    fun rotates(c: Context, t: Target) = !t.isDex && t in Library.get(c).rotating

    /**
     * A screen that turns sideways needs pixels for both orientations, so it gets the whole image,
     * pre-scaled only as far as keeps its short edge at least the screen's long edge and the
     * user's crops at full screen resolution. The user's crops go along, scaled to match: portrait
     * and landscape in one write when a landscape crop is set, else the portrait crop as the hint
     * Android derives the landscape view from.
     */
    private fun applyRotating(c: Context, wm: WallpaperManager, wp: Wallpaper, crop: Crop, which: Int) {
        val (short, long) = Screens.size(c, wp.target.screen)
        var f = 1f
        if (Library.get(c).downsample != "off") {
            val need = listOfNotNull(
                long.toFloat() / minOf(wp.width, wp.height),
                short.toFloat() / crop.width, long.toFloat() / crop.height,
                wp.cropLand?.let { long.toFloat() / it.width }, wp.cropLand?.let { short.toFloat() / it.height },
            ).max()
            f = need.coerceAtMost(1f)
        }
        val scaledW = (wp.width * f).roundToInt()
        val scaled = f < 0.99f
        val file = if (scaled) Downsampler.render(c, wp, Crop(0, 0, wp.width, wp.height), scaledW) else File(wp.file)
        val k = if (scaled) scaledW.toFloat() / wp.width else 1f
        fun Crop.scaled() = Crop((left * k).roundToInt(), (top * k).roundToInt(), (right * k).roundToInt(), (bottom * k).roundToInt())
        val land = wp.cropLand
        if (land != null && setWithCrops(c, wm, wp.target, file, crop.scaled(), land.scaled(), which)) return
        FileInputStream(file).use { wm.setStream(it, crop.scaled().toRect(), true, which) }
    }

    /**
     * Portrait and landscape crops in one write, keyed by the screen size in each orientation
     * (WallpaperManager.setStreamWithCrops, Android 15+, not in the public SDK). False when the
     * call isn't available, so the caller falls back to the portrait crop alone.
     */
    private fun setWithCrops(c: Context, wm: WallpaperManager, t: Target, file: File, portrait: Crop, land: Crop, which: Int): Boolean {
        if (Build.VERSION.SDK_INT < 35) return false
        val (short, long) = Screens.size(c, t.screen)
        val crops = mapOf(Point(short, long) to portrait.toRect(), Point(long, short) to land.toRect())
        return runCatching {
            val m = WallpaperManager::class.java.getMethod(
                "setStreamWithCrops", InputStream::class.java, Map::class.java, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )
            FileInputStream(file).use { m.invoke(wm, it, crops, true, which) }
        }.onFailure { Log.w(Engine.TAG, "setStreamWithCrops unavailable, landscape crop not applied: $it") }.isSuccess
    }

    /** Phones without a cover screen have no main-display flag: plain SYSTEM / LOCK there. */
    private fun which(c: Context, t: Target) = if (t.screen == Screen.MAIN && !Screens.foldable(c)) t.which and 3 else t.which

    fun key(wp: Wallpaper, t: Target, c: Context) =
        "${wp.id}:${File(wp.file).lastModified()}:${wp.cropFor(t)}:${wp.cropLandFor(t)}:${downsampleWidth(c, t)}:${rotates(c, t)}"

    /**
     * Width to pre-scale a non-rotating slot's crop to, or null to hand Android the original. DeX
     * slots follow the setting (DeX display or a fixed width); phone screens, whenever pre-scaling
     * is on, get their own panel's width (the portrait crop's short side). Android then decodes a
     * screen-sized image instead of the full original (a 4352×7680 photo is 134 MB decoded in
     * system_server). Rotating screens are scaled differently (see [applyRotating]).
     */
    fun downsampleWidth(c: Context, t: Target): Int? = when {
        t.isDex -> downsampleWidth(c)
        Library.get(c).downsample == "off" -> null
        else -> Screens.size(c, t.screen).first
    }

    /** Target width for downsampling, or null when off. */
    fun downsampleWidth(c: Context): Int? = when (val d = Library.get(c).downsample) {
        "off" -> null.also { Downsampler.clear(c) }
        "dex" -> detectDexWidth(c)?.also { w -> if (w != Library.get(c).lastDexWidth) Library.update(c) { it.copy(lastDexWidth = w) } }
            ?: Library.get(c).lastDexWidth
        else -> d.toIntOrNull()
    }

    fun detectDexWidth(c: Context): Int? = c.getSystemService(DisplayManager::class.java).displays
        .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.flags and Display.FLAG_PRIVATE == 0 }
        ?.mode?.let { maxOf(it.physicalWidth, it.physicalHeight) }

    /** Largest centred crop of the given aspect (w/h). */
    fun centreCrop(w: Int, h: Int, aspect: Float): Crop =
        if (w.toFloat() / h > aspect) {
            val cw = (h * aspect).roundToInt(); val x = (w - cw) / 2
            Crop(x, 0, x + cw, h)
        } else {
            val ch = (w / aspect).roundToInt(); val y = (h - ch) / 2
            Crop(0, y, w, y + ch)
        }

    /** Crop aspect (w/h) for a slot: the DeX setting or monitor, else the phone screen's shape. */
    fun aspect(c: Context, t: Target): Float = if (t.isDex) aspect(c) else Screens.aspect(c, t.screen)

    /** Aspect for new DeX crops: the setting, else a connected external (DeX) display, else the last one seen. */
    fun aspect(c: Context): Float {
        val s = Library.get(c)
        if (s.aspect != "auto") return parseAspect(s.aspect) ?: 1.6f
        return detectDexAspect(c)?.also { a ->
            if (a != s.lastDexAspect) Library.update(c) { it.copy(lastDexAspect = a) }
        } ?: s.lastDexAspect
    }

    fun parseAspect(s: String): Float? = s.split(":").takeIf { it.size == 2 }
        ?.let { (w, h) -> w.toFloatOrNull()?.let { wf -> h.toFloatOrNull()?.let { wf / it } } }

    fun detectDexAspect(c: Context): Float? {
        for (d in c.getSystemService(DisplayManager::class.java).displays) {
            if (d.displayId == Display.DEFAULT_DISPLAY) continue
            val m = d.mode
            val w = maxOf(m.physicalWidth, m.physicalHeight); val h = minOf(m.physicalWidth, m.physicalHeight)
            if (h > 0) return w.toFloat() / h
        }
        return null
    }

    /**
     * Copies the slot's current original image and crop hint into the gallery, so re-applying
     * it gives the same picture without cropping again. Uses the hidden
     * getWallpaperFile(which, getCropped=false) and Samsung's semGetWallpaperCropHint; both need
     * All files access on One UI. Without the original, the on-screen (already cropped) file is
     * imported whole. An empty hint means the system showed the whole original.
     */
    fun importCurrent(c: Context, target: Target): Wallpaper {
        val wm = WallpaperManager.getInstance(c)
        val original = runCatching {
            WallpaperManager::class.java.getMethod("getWallpaperFile", Int::class.java, Boolean::class.java)
                .invoke(wm, which(c, target), false) as ParcelFileDescriptor?
        }.getOrNull()
        val pfd = original ?: wm.getWallpaperFile(which(c, target)) ?: error("No image wallpaper set on the ${target.phrase}")
        val tmp = File(c.cacheDir, "current_${target.key}")
        pfd.use { p -> FileInputStream(p.fileDescriptor).use { input -> tmp.outputStream().use { input.copyTo(it) } } }
        val imported = Images.importFile(c, tmp, source = null, reuseUpscaled = false)
        tmp.delete()
        val whole = Crop(0, 0, imported.width, imported.height)
        val crop = if (original == null) whole else {
            val hint = runCatching {
                WallpaperManager::class.java.getMethod("semGetWallpaperCropHint", Int::class.java).invoke(wm, which(c, target)) as Rect?
            }.getOrNull()
            hint?.takeIf { !it.isEmpty && it.left >= 0 && it.top >= 0 && it.right <= imported.width && it.bottom <= imported.height }
                ?.let { Crop(it.left, it.top, it.right, it.bottom) } ?: whole
        }
        return Wallpaper(
            id = UUID.randomUUID().toString(), target = target,
            name = "Current ${target.phrase}", file = imported.file.path,
            width = imported.width, height = imported.height, crop = crop,
        )
    }
}
