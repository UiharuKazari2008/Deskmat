package dev.dexwp.backdrop.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.LruCache
import dev.dexwp.backdrop.data.Library
import dev.dexwp.backdrop.model.Crop
import java.io.File
import java.io.InputStream
import java.util.UUID

/** Copies images into app storage (EXIF rotation baked in) and decodes previews. */
object Images {
    private const val MAX_SIDE = 8192
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** [source]: the original's path on the phone's storage, when known. */
    class Imported(val file: File, val width: Int, val height: Int, val source: String? = null)

    /** Drops the decoded previews (when the UI is hidden or memory is low; the watcher service keeps the process alive). */
    fun trim() {
        cache.evictAll()
        seeds.evictAll()
    }

    fun importUri(c: Context, uri: Uri): Imported =
        import(c, sourcePath(c, uri), reuseUpscaled = true) { c.contentResolver.openInputStream(uri) ?: error("cannot open $uri") }

    /** [reuseUpscaled] = false when the caller relies on the original's size (e.g. a crop hint). */
    fun importFile(c: Context, src: File, source: String? = src.path, reuseUpscaled: Boolean = true): Imported =
        import(c, source, reuseUpscaled) { src.inputStream() }

    /** The picked image's path on the phone's storage, from MediaStore (null when it doesn't say). */
    fun sourcePath(c: Context, uri: Uri): String? = runCatching {
        c.contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) cur.getString(0)?.takeIf { it.startsWith("/storage/") } else null
        }
    }.getOrNull()

    fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input -> val buf = ByteArray(1 shl 16); while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * The gallery file holding the same picture as [f], so copies share one file (and are only
     * upscaled once): an identical file, or with [reuseUpscaled] the upscaled version of it.
     */
    fun sameImage(c: Context, f: File, reuseUpscaled: Boolean): File? {
        val hash = sha256(f)
        if (reuseUpscaled) Library.get(c).wallpapers.firstOrNull { it.upscaledFrom == hash && File(it.file).exists() }?.let { return File(it.file) }
        val len = f.length()
        return Library.get(c).wallpapers.asSequence().map { File(it.file) }.distinct()
            .firstOrNull { it != f && it.exists() && it.length() == len && sha256(it) == hash }
    }

    private fun import(c: Context, source: String?, reuseUpscaled: Boolean, open: () -> InputStream): Imported {
        val out = File(Library.imagesDir(c), "${UUID.randomUUID()}.jpg")
        val degrees = open().use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }
        if (degrees == 0) {
            open().use { input -> out.outputStream().use { input.copyTo(it) } }
        } else {
            // The system wallpaper cropper ignores EXIF orientation, so rotate the pixels.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open().use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
            val src = open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: error("cannot decode image")
            val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
            out.outputStream().use { rotated.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        }
        size(out) ?: run { out.delete(); error("not an image") }
        // Already in the gallery: share that file instead of keeping a second copy.
        val file = sameImage(c, out, reuseUpscaled)?.also { out.delete() } ?: out
        val (w, h) = size(file) ?: error("not an image")
        return Imported(file, w, h, source)
    }

    fun size(f: File): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    /** Decodes [crop] (or the whole image) so the longer side is about [maxSide] px. Cached. */
    fun preview(path: String, crop: Crop?, maxSide: Int): Bitmap? {
        val f = File(path)
        val key = "$path:${f.lastModified()}:$crop:$maxSide"
        cache.get(key)?.let { return it }
        if (!f.isFile) return null
        val (w, h) = size(f) ?: return null
        val region = crop?.toRect() ?: android.graphics.Rect(0, 0, w, h)
        var sample = 1
        while (maxOf(region.width(), region.height()) / (sample * 2) >= maxSide) sample *= 2
        val bmp = runCatching {
            val d = BitmapRegionDecoder.newInstance(f.path)
            try {
                d.decodeRegion(region, BitmapFactory.Options().apply { inSampleSize = sample })
            } finally {
                d.recycle()
            }
        }.getOrNull() ?: return null
        cache.put(key, bmp)
        return bmp
    }

    private val seeds = LruCache<String, Int>(64)
    /** Dominant colour of the (cropped) image, as Android derives wallpaper colours. */
    fun seedColor(path: String, crop: Crop?): Int? {
        val key = "$path:${File(path).lastModified()}:$crop"
        seeds.get(key)?.let { return it }
        val bmp = preview(path, crop, 256) ?: return null
        val c = android.app.WallpaperColors.fromBitmap(bmp).primaryColor.toArgb()
        seeds.put(key, c)
        return c
    }

    fun deleteIfUnused(c: Context, path: String?) {
        if (path == null) return
        if (Library.get(c).wallpapers.none { it.file == path }) File(path).delete()
    }
}
