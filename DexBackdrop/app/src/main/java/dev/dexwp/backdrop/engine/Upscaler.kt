package dev.dexwp.backdrop.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import dev.dexwp.backdrop.data.Library
import dev.dexwp.backdrop.model.Crop
import dev.dexwp.backdrop.model.Target
import dev.dexwp.backdrop.model.Wallpaper
import java.io.File
import java.util.UUID

/**
 * Low-resolution check and on-device upscaling (Real-ESRGAN x4plus-anime through ncnn, native
 * library "upscaler"). An image counts as low resolution on a screen when its crop has to be
 * stretched by [LOW_RES] or more to fill it.
 */
object Upscaler {
    const val LOW_RES = 1.5f
    /** Largest input upscaled (pixels); 4× that is decoded in memory as the result. */
    const val MAX_INPUT = 1_600_000

    private val loaded by lazy { runCatching {
        // ncnn's OpenMP aborts the app when it can't read or set CPU affinity (seen when started from
        // the app window on DeX); pinning threads gains nothing here, so turn it off before loading.
        android.system.Os.setenv("KMP_AFFINITY", "disabled", true)
        android.system.Os.setenv("OMP_PROC_BIND", "false", true)
        System.loadLibrary("upscaler")
    }.onFailure { Log.w(Engine.TAG, "upscaler library: $it") }.isSuccess }

    /** Width of the screen [t] shows on, in pixels (portrait for phone screens). */
    fun screenWidth(c: Context, t: Target): Int =
        if (t.isDex) Applier.detectDexWidth(c) ?: Library.get(c).lastDexWidth else Screens.size(c, t.screen).first

    /** How much [wp] (as seen by its own slot, see [Wallpaper.on]) is stretched to fill a screen [screenW] wide. */
    fun stretch(wp: Wallpaper, screenW: Int, aspect: Float): Float {
        val crop = wp.crop ?: Applier.centreCrop(wp.width, wp.height, aspect)
        return screenW.toFloat() / crop.width.coerceAtLeast(1)
    }

    fun canUpscale(wp: Wallpaper) = wp.width.toLong() * wp.height <= MAX_INPUT

    /** Called per finished tile; return false to cancel. */
    fun interface Progress { fun onTile(done: Int, total: Int): Boolean }

    /**
     * Upscales [wp]'s picture 4× and points every gallery entry showing the same picture at the
     * result (entries sharing the file, and separate files with identical content), with their
     * crops scaled to match. A picture already upscaled once is reused, not upscaled again.
     * Returns the saved copy's path when [Library] has saveUpscaled on. Blocking; call off the main thread.
     */
    fun upscale(c: Context, wp: Wallpaper, progress: Progress): Result<String?> = runCatching {
        check(canUpscale(wp)) { "the image is already ${wp.width}×${wp.height}" }
        val old = File(wp.file)
        val hash = Images.sha256(old)
        val len = old.length()
        val same = Library.get(c).wallpapers.map { it.file }.distinct()
            .filter { it == wp.file || File(it).let { f -> f.exists() && f.length() == len && Images.sha256(f) == hash } }.toSet()
        // The original on the phone's storage, found before the gallery copy goes away.
        val source = Library.get(c).wallpapers.firstNotNullOfOrNull { x -> x.source?.takeIf { x.file in same } } ?: findSource(c, old, hash)
        val done = Library.get(c).wallpapers.firstOrNull { it.upscaledFrom == hash && File(it.file).exists() }
        val (file, w, h) = if (done != null) Triple(File(done.file), done.width, done.height) else {
            check(loaded) { "the upscaler isn't available on this phone" }
            val src = BitmapFactory.decodeFile(wp.file, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
                ?: error("cannot read the image")
            val input = if (src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, false)
            val out = Bitmap.createBitmap(input.width * 4, input.height * 4, Bitmap.Config.ARGB_8888)
            val started = System.currentTimeMillis()
            val ok = nativeUpscale(c.assets, input, out, progress)
            input.recycle()
            if (!ok) { out.recycle(); error("upscaling stopped") }
            val f = File(Library.imagesDir(c), "${UUID.randomUUID()}.jpg")
            f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            Log.i(Engine.TAG, "upscaled ${wp.name} to ${out.width}×${out.height} in ${System.currentTimeMillis() - started} ms")
            Triple(f, out.width, out.height).also { out.recycle() }
        }
        val k = w.toFloat() / wp.width
        fun Crop.scaled() = Crop((left * k).toInt(), (top * k).toInt(), (right * k).toInt().coerceAtMost(w), (bottom * k).toInt().coerceAtMost(h))
        Library.update(c) { s ->
            s.copy(wallpapers = s.wallpapers.map { x ->
                if (x.file !in same) x else x.copy(
                    file = file.path, width = w, height = h,
                    crop = x.crop?.scaled(), cropLand = x.cropLand?.scaled(),
                    crops = x.crops.mapValues { it.value.scaled() }, cropsLand = x.cropsLand.mapValues { it.value.scaled() },
                    source = x.source ?: source, upscaledFrom = hash,
                )
            })
        }
        val left = Library.get(c).wallpapers.map { it.file }.toSet()
        same.filter { it !in left }.forEach { File(it).delete() }
        if (Library.get(c).saveUpscaled) saveCopy(c, Library.get(c).wallpapers.first { it.file == file.path }).getOrNull() else null
    }

    /** Finds the picture's original in the phone's photos (same size and content), or null. */
    private fun findSource(c: Context, f: File, hash: String): String? = runCatching {
        val uri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val cols = arrayOf(android.provider.MediaStore.MediaColumns._ID, android.provider.MediaStore.MediaColumns.DATA)
        c.contentResolver.query(uri, cols, "${android.provider.MediaStore.MediaColumns.SIZE} = ?", arrayOf(f.length().toString()), null)?.use { cur ->
            while (cur.moveToNext()) {
                val path = cur.getString(1) ?: continue
                val p = File(path)
                if (p.canRead() && Images.sha256(p) == hash) return@runCatching path
            }
            null
        }
    }.getOrNull()

    /**
     * Saves the upscaled picture as "<name>_upscaled.jpg" next to its original, or in
     * Pictures/Deskmat when the original's folder is unknown or can't be written. Returns the path.
     */
    fun saveCopy(c: Context, wp: Wallpaper): Result<String> = runCatching {
        val src = File(wp.file)
        val origin = wp.source?.let(::File)
        val base = (origin?.nameWithoutExtension ?: wp.name).replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val name = "${base}_upscaled.jpg"
        val root = android.os.Environment.getExternalStorageDirectory().path
        // All files access: write straight into the original's folder (any folder).
        val dir = origin?.parentFile
        if (dir != null && android.os.Environment.isExternalStorageManager() && dir.canWrite()) {
            val out = uniqueFile(dir, name)
            src.inputStream().use { input -> out.outputStream().use { input.copyTo(it) } }
            android.media.MediaScannerConnection.scanFile(c, arrayOf(out.path), arrayOf("image/jpeg"), null)
            return@runCatching out.path
        }
        // Otherwise through MediaStore, into the original's folder when Android allows it there.
        val rel = dir?.path?.removePrefix(root)?.trim('/')?.plus("/")
        for (r in listOfNotNull(rel, "Pictures/Deskmat/")) {
            val v = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, r)
            }
            val uri = runCatching { c.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) }.getOrNull() ?: continue
            c.contentResolver.openOutputStream(uri)?.use { o -> src.inputStream().use { it.copyTo(o) } } ?: continue
            return@runCatching "$root/$r$name"
        }
        error("couldn't save the copy")
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        var i = 2
        while (f.exists()) { f = File(dir, name.replace("_upscaled.jpg", "_upscaled ($i).jpg")); i++ }
        return f
    }

    private external fun nativeUpscale(assets: android.content.res.AssetManager, input: Bitmap, output: Bitmap, progress: Progress): Boolean
}
