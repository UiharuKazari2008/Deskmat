package dev.dexwp.backdrop.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import dev.dexwp.backdrop.model.Crop
import dev.dexwp.backdrop.model.Wallpaper
import java.io.File
import kotlin.math.roundToInt

/**
 * Quality-first downscale of a crop to a target width, done on the phone so the system's own
 * (aliasing) scaler never has to shrink it. Steps: decoder subsampling down to no less than
 * 2× the target, then repeated filtered halving, then one final filtered resize. Saved as a
 * baseline sRGB JPEG, which Android decodes fastest. Results are cached per crop and width.
 */
object Downsampler {
    /** Deletes the pre-scaled copies (downsampling turned off: they'd never be used again). */
    fun clear(c: Context) {
        File(c.cacheDir, "rendered").listFiles()?.forEach { it.delete() }
    }

    /** [dirName]: cache folder; one rendering per image is kept in each. */
    fun render(c: Context, wp: Wallpaper, crop: Crop, targetW: Int, dirName: String = "rendered"): File {
        val dir = File(c.cacheDir, dirName).apply { mkdirs() }
        val src = File(wp.file)
        val out = File(dir, "${wp.id}_${src.lastModified()}_${crop.left}_${crop.top}_${crop.right}_${crop.bottom}_$targetW.jpg")
        if (out.isFile) return out
        dir.listFiles { f -> f.name.startsWith("${wp.id}_") }?.forEach { it.delete() }

        val targetH = (crop.height.toFloat() * targetW / crop.width).roundToInt()
        var sample = 1
        while (crop.width / (sample * 2) >= targetW * 2) sample *= 2
        val decoder = BitmapRegionDecoder.newInstance(src.path)
        var bmp: Bitmap = try {
            decoder.decodeRegion(crop.toRect(), BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: error("cannot decode ${src.name}")
        } finally {
            decoder.recycle()
        }
        while (bmp.width / 2 >= targetW) {
            val half = Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true)
            bmp.recycle()
            bmp = half
        }
        if (bmp.width != targetW) {
            val exact = Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
            bmp.recycle()
            bmp = exact
        }
        val tmp = File(dir, out.name + ".tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        tmp.renameTo(out)
        return out
    }
}
