package dev.dexwp.backdrop.remote

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import dev.dexwp.backdrop.data.Library
import dev.dexwp.backdrop.data.LibraryState
import dev.dexwp.backdrop.engine.Applier
import dev.dexwp.backdrop.engine.Commands
import dev.dexwp.backdrop.engine.Downsampler
import dev.dexwp.backdrop.engine.Engine
import dev.dexwp.backdrop.engine.Screens
import dev.dexwp.backdrop.model.Crop
import dev.dexwp.backdrop.model.Group
import dev.dexwp.backdrop.model.Target
import dev.dexwp.backdrop.model.Wallpaper
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

/**
 * Lets apps the user allowed ([RemoteAccess]) read and drive Deskmat. Spec: docs/REMOTE_API.md.
 *
 *   content://dev.dexwp.backdrop.remote/status                      one row per slot
 *   content://dev.dexwp.backdrop.remote/current/<slot>[/full|/cropped]
 *   content://dev.dexwp.backdrop.remote/groups[?slot=]               groups/<id>, groups/<id>/images[?slot=]
 *   content://dev.dexwp.backdrop.remote/images[?slot=]               images/<id>[/full|/cropped]
 *   content://dev.dexwp.backdrop.remote/flags
 *   call(): access, next, set, clear, flag, automatic, shuffle, refresh, command
 *
 * Reads need [Scope.READ]; call() methods that change anything need [Scope.CONTROL].
 */
class RemoteProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        appContext = context?.applicationContext
        return true
    }

    private val ctx: Context get() = context!!

    private fun held(): Scope? = RemoteAccess.scopeOf(ctx, Binder.getCallingUid(), callingPackage)

    private fun need(needed: Scope) {
        if (!RemoteAccess.allows(held(), needed)) {
            throw SecurityException("Deskmat: ${callingPackage ?: "this app"} needs “${needed.key}” access; ask with the $ACTION_REQUEST_ACCESS activity")
        }
    }

    // ---- Rows ----

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        need(Scope.READ)
        val s = Library.get(ctx)
        val seg = uri.pathSegments
        val slotParam = uri.getQueryParameter("slot")?.let { Target.of(it) ?: throw IllegalArgumentException("unknown slot “$it”") }
        // A file URI queried for its name and size (what image loaders and share targets do).
        if (seg.lastOrNull() in FILE_KINDS) return openable(uri, projection)
        val cursor = when {
            seg == listOf("status") -> status(s, slots())
            seg.size == 2 && seg[0] == "current" -> status(s, listOf(slot(seg[1])))
            seg == listOf("flags") -> MatrixCursor(FLAG_COLS).apply { s.flags.toSortedMap().forEach { (k, v) -> addRow(arrayOf(k, if (v) 1 else 0)) } }
            seg == listOf("groups") -> groups(s, slotParam?.let { t -> s.groupsOn(t) + s.unassigned(t) }
                ?: (s.groups.filter { !it.isUnassigned } + slots().map { s.unassigned(it) }))
            seg.size == 2 && seg[0] == "groups" -> groups(s, listOf(group(s, seg[1])))
            seg.size == 3 && seg[0] == "groups" && seg[2] == "images" -> {
                val g = group(s, seg[1])
                val t = slotParam ?: g.target
                val key = s.pickKey(g, t)
                images(s, s.inGroup(g, t), t, g, s.picks[key])
            }
            seg == listOf("images") -> images(s, slotParam?.let { t -> s.wallpapers.filter { s.canShow(it, t) } } ?: s.wallpapers, slotParam)
            seg.size == 2 && seg[0] == "images" -> images(s, listOf(image(s, seg[1])), slotParam)
            else -> throw IllegalArgumentException("unknown URI $uri")
        }
        return cursor.also { it.setNotificationUri(ctx.contentResolver, uri) }
    }

    private fun slots() = Target.entries.filter { it.screen in Screens.available(ctx) }

    private fun slot(key: String) = Target.of(key) ?: throw IllegalArgumentException("unknown slot “$key”; slots: ${Target.entries.joinToString { it.key }}")
    private fun group(s: LibraryState, id: String) = s.group(id) ?: s.findGroup(id) ?: throw IllegalArgumentException("no group “$id”")
    private fun image(s: LibraryState, id: String) = s.byId(id) ?: throw IllegalArgumentException("no image “$id”")

    private fun status(s: LibraryState, slots: List<Target>) = MatrixCursor(STATUS_COLS).apply {
        slots.forEach { t ->
            val st = s.status[t]
            val wp = s.byId(st?.wallpaperId)
            val g = s.pinnedGroup(t) ?: s.activeGroupOf(t)
            val next = runCatching { Engine.upcoming(ctx, t) }.getOrNull()
            addRow(arrayOf(
                t.key, t.label, t.screen.name.lowercase(), if (t.lock) 1 else 0,
                wp?.id, wp?.name, g.id, g.name, if (g.shuffle) 1 else 0, st?.reason, st?.at ?: 0,
                if (s.pins[t] != null) 1 else 0, s.pins[t], if (t in s.autoOn) 1 else 0,
                next?.id, next?.name,
                wp?.let { fileUri("current/${t.key}/full") }, wp?.let { fileUri("current/${t.key}/cropped") },
            ))
        }
    }

    private fun groups(s: LibraryState, gs: List<Group>) = MatrixCursor(GROUP_COLS).apply {
        gs.distinctBy { it.id }.forEach { g ->
            val activeOn = Target.entries.filter { s.activeGroup[it] == g.id || s.pinnedGroup(it)?.id == g.id }
            addRow(arrayOf(
                g.id, g.target.key, g.name, g.command, if (g.shuffle) 1 else 0, if (g.global) 1 else 0,
                g.links.joinToString(",") { it.key }, if (g.isUnassigned) 1 else 0,
                s.inGroup(g).size, activeOn.joinToString(",") { it.key }, g.triggers.size, s.picks[s.pickKey(g, g.target)],
            ))
        }
    }

    /** [view]: the slot whose crop to describe (null = the image's own). [g]/[current]: extra columns for a group listing. */
    private fun images(s: LibraryState, wps: List<Wallpaper>, view: Target?, g: Group? = null, current: String? = null) = MatrixCursor(IMAGE_COLS).apply {
        wps.forEachIndexed { i, w ->
            val t = view ?: w.target
            val q = if (t == w.target) "" else "?slot=${t.key}"
            val media = w.source?.let { mediaId(it) }
            addRow(arrayOf(
                i.toLong(), w.id, w.target.key, w.name, w.command, w.width, w.height,
                w.cropFor(t)?.str(), w.cropLandFor(t)?.str(), s.groupsOf(w).joinToString(",") { it.id },
                w.source, media, media?.let { ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it).toString() },
                w.file, fileUri("images/${w.id}/full"), fileUri("images/${w.id}/cropped$q"), if (w.upscaledFrom != null) 1 else 0,
                if (g != null) i else null, g?.let { if (w.id in it.skipped) 1 else 0 }, g?.let { if (w.id == current) 1 else 0 },
            ))
        }
    }

    private fun Crop.str() = "$left,$top,$right,$bottom"

    // ---- Files ----

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        need(Scope.READ)
        if (mode != "r") throw SecurityException("Deskmat images are read-only")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** The image file for images/<id>/(full|cropped) or current/<slot>/(full|cropped). */
    private fun file(uri: Uri): File {
        val s = Library.get(ctx)
        val seg = uri.pathSegments
        if (seg.size != 3 || seg[2] !in FILE_KINDS) throw FileNotFoundException("not a file URI: $uri")
        val (wp, t) = when (seg[0]) {
            "images" -> image(s, seg[1]).let { w -> w to (uri.getQueryParameter("slot")?.let(::slot) ?: w.target) }
            "current" -> slot(seg[1]).let { t -> (s.byId(s.status[t]?.wallpaperId) ?: throw FileNotFoundException("nothing set on ${t.phrase}")) to t }
            else -> throw FileNotFoundException("not a file URI: $uri")
        }
        if (seg[2] == "full") return File(wp.file).takeIf { it.isFile } ?: throw FileNotFoundException("image file missing")
        val land = uri.getQueryParameter("orientation").equals("landscape", true) && !t.isDex
        val crop = cropOf(wp, t, land)
        val width = uri.getQueryParameter("width")?.toIntOrNull()?.coerceIn(1, crop.width) ?: crop.width
        return Downsampler.render(ctx, wp, crop, width, "remote")
    }

    /** The crop the slot shows: the saved one, else the centred one Deskmat would apply. */
    private fun cropOf(wp: Wallpaper, t: Target, land: Boolean): Crop {
        val saved = if (land) wp.cropLandFor(t) else wp.cropFor(t)
        if (saved != null) return saved
        val a = Applier.aspect(ctx, t)
        return Applier.centreCrop(wp.width, wp.height, if (land) 1f / a else a)
    }

    private fun openable(uri: Uri, projection: Array<out String>?): Cursor {
        val f = file(uri)
        val cols = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply { addRow(cols.map { if (it == OpenableColumns.SIZE) f.length() else f.name }.toTypedArray()) }
    }

    override fun getType(uri: Uri): String? {
        val seg = uri.pathSegments
        return when {
            seg.lastOrNull() == "cropped" -> "image/jpeg"
            seg.lastOrNull() == "full" -> runCatching {
                BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(file(uri).path, it) }.outMimeType
            }.getOrNull() ?: "image/*"
            seg.isEmpty() -> null
            seg.size == 1 -> "vnd.android.cursor.dir/vnd.$AUTHORITY.${seg[0]}"
            else -> "vnd.android.cursor.item/vnd.$AUTHORITY.${seg[0]}"
        }
    }

    // ---- Commands ----

    /**
     * Control methods. Extras: "slot" (home, lock, main, mainlock, cover, coverlock), "state"
     * (on, off, toggle). [arg]: the image, group or flag. Returns "ok" (boolean) and "reply".
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (method == "access") return Bundle().apply { putString("scope", held()?.key) }
        need(Scope.CONTROL)
        val slot = extras?.getString("slot")?.let { Target.of(it) ?: return reply("error: unknown slot “$it”") }
        val state = extras?.getString("state")
        val c = ctx
        val token = Binder.clearCallingIdentity()
        try {
            return reply(when (method) {
                "next" -> Commands.execute(c, "next", null, slot, waitMs = WAIT_MS)
                "set" -> Commands.execute(c, "set", arg ?: return reply("error: arg (image or group) missing"), slot, waitMs = WAIT_MS)
                "clear" -> Commands.execute(c, "clear", null, slot, waitMs = WAIT_MS)
                "flag" -> Commands.execute(c, "flag", arg, null, state ?: "on", waitMs = WAIT_MS)
                "automatic" -> Commands.execute(c, "automatic", null, slot, state ?: "on", waitMs = WAIT_MS)
                "shuffle" -> setShuffle(c, arg, slot, state)
                "refresh" -> Commands.execute(c, "refresh", null, null, waitMs = WAIT_MS)
                "command" -> Commands.execute(
                    c, extras?.getString("cmd") ?: return reply("error: extra “cmd” missing"),
                    extras.getString("arg") ?: arg, slot ?: Target.of(extras.getString("target")), state, WAIT_MS,
                )
                else -> "error: unknown method “$method”. Methods: access, next, set, clear, flag, automatic, shuffle, refresh, command"
            })
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    /** Turns a group's Shuffle on/off/toggle ([arg] = group, else the slot's active group). */
    private fun setShuffle(c: Context, arg: String?, slot: Target?, state: String?): String {
        val s = Library.get(c)
        val g = arg?.let { s.group(it) ?: s.findGroup(it, slot) } ?: slot?.let { s.pinnedGroup(it) ?: s.activeGroupOf(it) }
            ?: return "error: give a group (arg) or a slot"
        val on = when (state?.lowercase()) { "on", "true", "1" -> true; "off", "false", "0" -> false; else -> !g.shuffle }
        Library.update(c) { it.withGroup(g.id) { x -> x.copy(shuffle = on) }.copy(queued = it.queued.filterKeys { k -> !k.startsWith(g.id) }) }
        return "${g.name}: shuffle ${if (on) "on" else "off"}"
    }

    private fun reply(text: String) = Bundle().apply { putBoolean("ok", !text.startsWith("error")); putString("reply", text) }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("use call()")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException("use call()")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException("use call()")

    companion object {
        const val AUTHORITY = "dev.dexwp.backdrop.remote"
        const val ACTION_REQUEST_ACCESS = "dev.dexwp.backdrop.REQUEST_ACCESS"
        val BASE: Uri = Uri.parse("content://$AUTHORITY")
        private const val WAIT_MS = 5_000L
        private val FILE_KINDS = setOf("full", "cropped")

        private val STATUS_COLS = arrayOf(
            "slot", "label", "screen", "lock", "wallpaper_id", "wallpaper_name", "group_id", "group_name", "shuffle", "reason",
            "changed_at", "pinned", "pin", "automatic", "next_id", "next_name", "full_uri", "cropped_uri",
        )
        private val GROUP_COLS = arrayOf(
            "id", "slot", "name", "command", "shuffle", "global", "links", "unassigned", "image_count", "active_on", "triggers", "current_id",
        )
        private val IMAGE_COLS = arrayOf(
            "_id", "id", "slot", "name", "command", "width", "height", "crop", "crop_land", "groups",
            "source_path", "media_id", "media_uri", "internal_path", "full_uri", "cropped_uri", "upscaled",
            "position", "skipped", "current",
        )
        private val FLAG_COLS = arrayOf("name", "on")

        private fun fileUri(path: String) = "content://$AUTHORITY/$path"

        private val mediaIds = ConcurrentHashMap<String, Long>()
        private var appContext: Context? = null

        /** MediaStore id of a file on shared storage (needs All files access for other apps' files). */
        private fun mediaId(path: String): Long? {
            mediaIds[path]?.let { return it.takeIf { id -> id >= 0 } }
            val c = appContext ?: return null
            val id = runCatching {
                c.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DATA}=?", arrayOf(path), null,
                )?.use { if (it.moveToFirst()) it.getLong(0) else null }
            }.getOrNull()
            mediaIds[path] = id ?: -1
            return id
        }

        /** Tells observers (ContentObserver on any of these URIs) what changed. Called by [Library.update]. */
        fun notifyChanged(c: Context, prev: LibraryState, next: LibraryState) {
            appContext = c.applicationContext
            val r = c.contentResolver
            fun n(path: String) = r.notifyChange(Uri.withAppendedPath(BASE, path), null)
            val statusChanged = prev.status != next.status || prev.pins != next.pins || prev.activeGroup != next.activeGroup ||
                prev.autoOn != next.autoOn || prev.queued != next.queued || prev.picks != next.picks
            if (statusChanged) { n("status"); n("current") }
            if (prev.groups != next.groups || prev.picks != next.picks || prev.activeGroup != next.activeGroup || prev.wallpapers != next.wallpapers) n("groups")
            if (prev.wallpapers != next.wallpapers || prev.groups != next.groups) n("images")
            if (prev.flags != next.flags) n("flags")
        }

    }
}
