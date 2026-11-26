package dev.dexwp.backdrop.model

import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject

/** A physical screen whose wallpapers the app manages, in switcher order. */
enum class Screen(val label: String, val short: String) {
    COVER("Cover screen", "Cover"),
    MAIN("Main screen", "Main"),
    DESKTOP("DeX desktop", "Desktop"),
}

/**
 * Samsung wallpaper slots. `which` = SYSTEM (1) or LOCK (2), OR'd with the display:
 * main 4, DeX 8 (WallpaperManager.FLAG_DISPLAY_DEX), cover/sub 16.
 */
enum class Target(val which: Int, val screen: Screen, val lock: Boolean, val label: String, val key: String) {
    HOME(9, Screen.DESKTOP, false, "DeX desktop", "home"),
    LOCK(10, Screen.DESKTOP, true, "DeX lock screen", "lock"),
    MAIN(5, Screen.MAIN, false, "Main screen", "main"),
    MAIN_LOCK(6, Screen.MAIN, true, "Main lock screen", "mainlock"),
    COVER(17, Screen.COVER, false, "Cover screen", "cover"),
    COVER_LOCK(18, Screen.COVER, true, "Cover lock screen", "coverlock");

    val isDex get() = screen == Screen.DESKTOP

    /** The label inside a sentence: "the DeX desktop", "the main lock screen". */
    val phrase get() = if (isDex) label else label.lowercase()

    companion object {
        fun of(s: String?): Target? = entries.firstOrNull { it.key.equals(s, true) || it.name.equals(s, true) }
        fun of(screen: Screen, lock: Boolean) = entries.first { it.screen == screen && it.lock == lock }
    }
}

/** Crop in source-image pixels; passed to WallpaperManager as the visible crop hint. */
data class Crop(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    fun toRect() = Rect(left, top, right, bottom)
    override fun toString() = "$left,$top–$right,$bottom"
}

sealed interface Condition {
    /** Minutes since midnight; end may be before start (wraps past midnight). Days: 1 = Monday … 7 = Sunday. */
    data class Time(val startMin: Int, val endMin: Int, val days: Set<Int> = (1..7).toSet()) : Condition

    /** Any of: dawn, day, dusk, night (computed from sunrise/sunset at the current location). */
    data class Sun(val phases: Set<String>) : Condition

    data class Place(val label: String, val lat: Double, val lon: Double, val radius: Float) : Condition

    /** A named on/off switch set from Routines, Termux, shortcuts or the Flags screen. */
    data class Flag(val name: String, val on: Boolean = true) : Condition

    /** The phone is in dark mode ([dark]) or light mode. */
    data class Theme(val dark: Boolean) : Condition
}

/** All conditions must hold (AND). */
data class RuleGroup(val conditions: List<Condition> = emptyList())

/** When the active group moves on to its next image (only while Automatic is on for the slot). */
data class AutoRules(
    val onDexStart: Boolean = false,
    val onDexEnd: Boolean = true,
    /** For DeX slots the interval only runs while DeX is connected. */
    val interval: Boolean = false,
    val intervalMin: Int = 60,
    /** Each time the screen turns off, so the next unlock shows a new image (needs the screen watcher service). */
    val onScreenOff: Boolean = false,
    /** Foldables: each time the phone is folded / unfolded (needs the screen watcher service). */
    val onFold: Boolean = false,
    val onUnfold: Boolean = false,
    /** Automatic advances wait until the current image has been shown this long (0 = no minimum). */
    val minShowMin: Int = 0,
    /** Automatic advances wait until the current image has been seen: home screen opened (or lock screen shown) since it changed. */
    val mustBeSeen: Boolean = false,
) {
    /** Rules that need the screen watcher service to hear the event. */
    val needsWatcher get() = onScreenOff || onFold || onUnfold
}

/**
 * A collapsible set of images for one slot, like a playlist: [members] lists wallpaper ids in
 * order, and one image can be in several groups. The first group (in list order) whose triggers
 * match is active; with none matching, the slot's unassigned group is (images in no group).
 * Inside the active group the image advances in member order, or at random with [shuffle].
 */
data class Group(
    val id: String,
    val target: Target,
    val name: String,
    /** Active when any rule group matches (OR of ANDs). Empty = only via a pin. */
    val triggers: List<RuleGroup> = emptyList(),
    val shuffle: Boolean = false,
    /** Null = the default rules from Settings. */
    val auto: AutoRules? = null,
    val collapsed: Boolean = false,
    /** Name usable from shortcuts, Termux and broadcasts: `set <command>` makes this group active. */
    val command: String? = null,
    /**
     * Wallpaper ids in order. For the unassigned group this only orders its images; which images
     * it holds is worked out (those in no other group of the slot).
     */
    val members: List<String> = emptyList(),
    /** Members Automatic and Next skip in this group; they stay listed and can still be set by hand. */
    val skipped: Set<String> = emptySet(),
    /**
     * Other slots this group also drives (a linked group). While it is active it beats those
     * slots' own groups; every linked slot shows the same image and they change together, each
     * with its own crop of it ([Wallpaper.crops]). Its images belong to [target].
     */
    val links: Set<Target> = emptySet(),
    /**
     * A global group: it exists on every slot with the same name, triggers and rules, but each
     * slot fills it with its own images ([members] holds images of any slot). A slot's own groups
     * come before it. Slots in [links] share [target]'s images instead.
     */
    val global: Boolean = false,
) {
    val isUnassigned get() = id == unassignedId(target)
    val isLinked get() = links.isNotEmpty()

    /** Every slot the group drives: its own and the linked ones. */
    val targets get() = setOf(target) + links

    companion object {
        fun unassignedId(t: Target) = "unassigned_${t.key}"
        fun unassigned(t: Target) = Group(unassignedId(t), t, "Unassigned")
    }
}

data class Wallpaper(
    val id: String,
    val target: Target,
    val name: String,
    val file: String,
    val width: Int,
    val height: Int,
    val crop: Crop? = null,
    /**
     * Phone screens only: the crop used when the screen is turned sideways (One UI keeps a
     * portrait and a landscape rect). Null = Android derives it from [crop].
     */
    val cropLand: Crop? = null,
    /** Name usable from shortcuts, Termux and broadcasts, e.g. "work". */
    val command: String? = null,
    /** Crops for other slots that show this image through a linked group (null = centred). */
    val crops: Map<Target, Crop> = emptyMap(),
    /** Landscape crops for those slots, when they rotate. */
    val cropsLand: Map<Target, Crop> = emptyMap(),
    /** Where the image was imported from (absolute path on the phone's storage), when known. */
    val source: String? = null,
    /** SHA-256 of the file before it was upscaled; null when it never was. */
    val upscaledFrom: String? = null,
) {
    fun cropFor(t: Target) = if (t == target) crop else crops[t]
    fun cropLandFor(t: Target) = if (t == target) cropLand else cropsLand[t]

    /** The image as another slot sees it: that slot's crops in [crop] and [cropLand]. */
    fun on(t: Target) = if (t == target) this else copy(target = t, crop = crops[t], cropLand = cropsLand[t])

    /** Takes back edits made to [on] for slot [t] (name, command and that slot's crops). */
    fun withView(t: Target, v: Wallpaper) = if (t == target) v else copy(
        name = v.name, command = v.command,
        crops = v.crop?.let { crops + (t to it) } ?: (crops - t),
        cropsLand = v.cropLand?.let { cropsLand + (t to it) } ?: (cropsLand - t),
    )
}

// ---- JSON ----

object Json {
    fun wallpaper(o: JSONObject) = Wallpaper(
        id = o.getString("id"),
        target = Target.of(o.getString("target")) ?: Target.HOME,
        name = o.getString("name"),
        file = o.getString("file"),
        width = o.optInt("width"),
        height = o.optInt("height"),
        crop = o.optJSONArray("crop")?.let { Crop(it.getInt(0), it.getInt(1), it.getInt(2), it.getInt(3)) },
        cropLand = o.optJSONArray("cropLand")?.let { Crop(it.getInt(0), it.getInt(1), it.getInt(2), it.getInt(3)) },
        command = o.optString("command").ifBlank { null },
        crops = cropMap(o.optJSONObject("crops")),
        cropsLand = cropMap(o.optJSONObject("cropsLand")),
        source = o.optString("source").ifBlank { null },
        upscaledFrom = o.optString("upscaledFrom").ifBlank { null },
    )

    private fun cropMap(o: JSONObject?): Map<Target, Crop> = o?.keys()?.asSequence()?.mapNotNull { k ->
        val a = o.getJSONArray(k)
        Target.of(k)?.let { it to Crop(a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3)) }
    }?.toMap() ?: emptyMap()

    private fun cropMap(m: Map<Target, Crop>) = JSONObject().apply {
        m.forEach { (t, c) -> put(t.key, JSONArray(listOf(c.left, c.top, c.right, c.bottom))) }
    }

    fun wallpaper(w: Wallpaper): JSONObject = JSONObject()
        .put("id", w.id).put("target", w.target.key).put("name", w.name).put("file", w.file)
        .put("width", w.width).put("height", w.height)
        .put("crop", w.crop?.let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) })
        .put("cropLand", w.cropLand?.let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) })
        .put("command", w.command)
        .put("crops", cropMap(w.crops))
        .put("cropsLand", cropMap(w.cropsLand))
        .put("source", w.source)
        .put("upscaledFrom", w.upscaledFrom)

    fun group(o: JSONObject) = Group(
        id = o.getString("id"),
        target = Target.of(o.getString("target")) ?: Target.HOME,
        name = o.getString("name"),
        triggers = triggers(o.optJSONArray("triggers")),
        shuffle = o.optBoolean("shuffle"),
        auto = o.optJSONObject("auto")?.let(::auto),
        collapsed = o.optBoolean("collapsed"),
        command = o.optString("command").ifBlank { null },
        members = o.optJSONArray("members")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
        skipped = o.optJSONArray("skipped")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet(),
        links = o.optJSONArray("links")?.let { a -> (0 until a.length()).mapNotNull { Target.of(a.getString(it)) }.toSet() } ?: emptySet(),
        global = o.optBoolean("global"),
    )

    fun group(g: Group): JSONObject = JSONObject()
        .put("id", g.id).put("target", g.target.key).put("name", g.name)
        .put("triggers", triggers(g.triggers))
        .put("shuffle", g.shuffle).put("auto", g.auto?.let(::auto)).put("collapsed", g.collapsed)
        .put("command", g.command)
        .put("members", JSONArray(g.members))
        .put("skipped", JSONArray(g.skipped.toList()))
        .put("links", JSONArray(g.links.map { it.key }))
        .put("global", g.global)

    fun auto(o: JSONObject) = AutoRules(
        onDexStart = o.optBoolean("onDexStart"),
        onDexEnd = o.optBoolean("onDexEnd", true),
        interval = o.optBoolean("interval"),
        intervalMin = o.optInt("intervalMin", 60),
        onScreenOff = o.optBoolean("onScreenOff"),
        onFold = o.optBoolean("onFold"),
        onUnfold = o.optBoolean("onUnfold"),
        minShowMin = o.optInt("minShowMin"),
        mustBeSeen = o.optBoolean("mustBeSeen"),
    )

    fun auto(a: AutoRules): JSONObject = JSONObject()
        .put("onDexStart", a.onDexStart).put("onDexEnd", a.onDexEnd).put("interval", a.interval).put("intervalMin", a.intervalMin)
        .put("onScreenOff", a.onScreenOff)
        .put("onFold", a.onFold).put("onUnfold", a.onUnfold)
        .put("minShowMin", a.minShowMin).put("mustBeSeen", a.mustBeSeen)

    /** Pin value that makes a whole group active (pins otherwise hold a wallpaper id). */
    const val GROUP_PIN = "group:"

    /** OR list of AND lists. Also reads the per-wallpaper "groups" field of version 0.17 and earlier. */
    fun triggers(a: JSONArray?): List<RuleGroup> =
        if (a == null) emptyList() else (0 until a.length()).map { rules(a.getJSONArray(it)) }

    fun triggers(t: List<RuleGroup>) = JSONArray(t.map { g -> JSONArray(g.conditions.map(::condition)) })

    private fun rules(a: JSONArray) = RuleGroup((0 until a.length()).mapNotNull { condition(a.getJSONObject(it)) })

    private fun condition(o: JSONObject): Condition? = when (o.getString("type")) {
        "time" -> Condition.Time(o.getInt("start"), o.getInt("end"), ints(o.getJSONArray("days")))
        "sun" -> Condition.Sun(strings(o.getJSONArray("phases")))
        "place" -> Condition.Place(o.getString("label"), o.getDouble("lat"), o.getDouble("lon"), o.getDouble("radius").toFloat())
        "flag" -> Condition.Flag(o.getString("name"), o.optBoolean("on", true))
        "theme" -> Condition.Theme(o.optBoolean("dark", true))
        else -> null
    }

    private fun condition(c: Condition): JSONObject = when (c) {
        is Condition.Time -> JSONObject().put("type", "time").put("start", c.startMin).put("end", c.endMin)
            .put("days", JSONArray(c.days.sorted()))
        is Condition.Sun -> JSONObject().put("type", "sun").put("phases", JSONArray(c.phases.toList()))
        is Condition.Place -> JSONObject().put("type", "place").put("label", c.label).put("lat", c.lat)
            .put("lon", c.lon).put("radius", c.radius.toDouble())
        is Condition.Flag -> JSONObject().put("type", "flag").put("name", c.name).put("on", c.on)
        is Condition.Theme -> JSONObject().put("type", "theme").put("dark", c.dark)
    }

    private fun ints(a: JSONArray) = (0 until a.length()).map { a.getInt(it) }.toSet()
    private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }.toSet()
}
