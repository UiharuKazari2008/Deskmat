package dev.dexwp.backdrop.data

import android.content.Context
import dev.dexwp.backdrop.model.AutoRules
import dev.dexwp.backdrop.model.Group
import dev.dexwp.backdrop.model.Json
import dev.dexwp.backdrop.model.Target
import dev.dexwp.backdrop.model.Wallpaper
import dev.dexwp.backdrop.remote.RemoteProvider
import dev.dexwp.backdrop.routines.RoutineParams
import dev.dexwp.backdrop.widget.Widgets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Home screen widget look; each has its own padding setting. */
enum class WidgetStyle(val label: String, val key: String, val defaultPadding: Int) {
    MATERIAL("Material", "material", 0),
    ONEUI("One UI", "oneui", 0),
}

/** What each slot is showing now and why. */
data class SlotStatus(val wallpaperId: String? = null, val reason: String = "Not run yet", val at: Long = 0)

data class LibraryState(
    /** In display order; within a group this is the order Automatic advances in. */
    val wallpapers: List<Wallpaper> = emptyList(),
    /** User groups in priority order, plus the stored settings of each slot's unassigned group. */
    val groups: List<Group> = emptyList(),
    val flags: Map<String, Boolean> = emptyMap(),
    /** Manual pin per slot (from "Set now", a shortcut, Termux or a broadcast); beats triggers. */
    val pins: Map<Target, String> = emptyMap(),
    val status: Map<Target, SlotStatus> = emptyMap(),
    /** Group whose rules apply to each slot right now (set by every engine run). */
    val activeGroup: Map<Target, String> = emptyMap(),
    /** Crop aspect for new DeX crops: "auto" (DeX monitor) or "W:H". */
    val aspect: String = "auto",
    val lastDexAspect: Float = 1.6f,
    val lastAppliedKey: Map<Target, String> = emptyMap(),
    /** Geofence ids we are currently inside (from enter/exit events). */
    val inside: Set<String> = emptySet(),
    /** True while Play services geofences are registered; else places use last known location. */
    val geofencing: Boolean = false,
    val geofenceStatus: String = "Not set up",
    /** Slots where the active group advances by itself (the Automatic switch). */
    val autoOn: Set<Target> = emptySet(),
    /** Rules for groups that don't set their own. */
    val autoDefaults: AutoRules = AutoRules(),
    /** Current image of each group (kept while another group is active). */
    val picks: Map<String, String> = emptyMap(),
    /** Shuffling groups: the image they will show next, chosen ahead so widgets can preview it. */
    val queued: Map<String, String> = emptyMap(),
    /** When each slot last changed image, for the interval. */
    val changedAt: Map<Target, Long> = emptyMap(),
    /** Slots whose active group must advance on the next run. */
    val advanceDue: Set<Target> = emptySet(),
    /**
     * Fold/unfold advances waiting for their screen to be put away (slot → when asked), so the
     * change happens out of sight. Dropped if the slot changes some other way first.
     */
    val pendingHidden: Map<Target, Long> = emptyMap(),
    /** Slots whose pending advance was asked for by hand (Next, widgets, commands): it skips the minimum-time and seen rules. */
    val advanceManual: Set<Target> = emptySet(),
    /** Last observed DeX state, to detect connect/disconnect. */
    val dexActive: Boolean = false,
    /** Slots written while DeX was off; re-applied on the first run with DeX active. */
    val dexPending: Set<Target> = emptySet(),
    /** Flags published as "on"/"off" app shortcuts (what Modes and Routines can launch). */
    val shortcutFlags: Set<String> = emptySet(),
    /** "pinId|automatic choice when pinned"; a pin is released once the automatic choice changes. */
    val pinBase: Map<Target, String> = emptyMap(),
    /** App colours from the current image (else the system palette). */
    val themeFromImage: Boolean = true,
    /** Downsample the cropped image on the phone before applying: "off", "dex" or a width in px. */
    val downsample: String = "dex",
    /** Also save each upscaled image next to its original (or in Pictures/Deskmat). */
    val saveUpscaled: Boolean = false,
    val lastDexWidth: Int = 1920,
    /** Phone slots whose screen turns sideways (their wallpaper keeps pixels for both orientations). DeX never rotates. */
    val rotating: Set<Target> = setOf(Target.MAIN),
    /** Widget content padding in dp, per style. */
    val widgetPadding: Map<WidgetStyle, Int> = emptyMap(),
) {
    fun forTarget(t: Target) = wallpapers.filter { it.target == t }
    fun byId(id: String?) = wallpapers.firstOrNull { it.id == id }

    /** The slot's own user groups, in the priority order the user set (without the unassigned one). */
    fun ownGroups(t: Target) = groups.filter { it.target == t && !it.isUnassigned }

    /** Global groups from other slots that this slot fills with its own images (after its own groups). */
    fun globalsOn(t: Target) = groups.filter { it.global && it.target != t && t !in it.links && !it.isUnassigned }

    /** User groups whose images on this slot are this slot's own: its own groups, then global ones. */
    fun groupsFor(t: Target) = ownGroups(t) + globalsOn(t)

    /** The slot whose images [g] shows on [t]: [t] itself for a global group there, else the group's own slot. */
    fun contentSlot(g: Group, t: Target) = if (g.global && t != g.target && t !in g.links) t else g.target

    /** Slots that show the same image as [t] does through [g] and change with it. */
    fun syncSlots(g: Group, t: Target) = if (contentSlot(g, t) == g.target) g.targets else setOf(t)

    /** Key of [g]'s current (and queued) image on [t] in [picks] / [queued]; shared by synced slots. */
    fun pickKey(g: Group, t: Target) = if (contentSlot(g, t) == g.target) g.id else "${g.id}@${t.key}"

    /** Slots where a global group has images of its own (where `set <group>` pins it). */
    fun slotsWithImages(g: Group) = Target.entries.filter { t -> (t == g.target || g.global || t in g.links) && autoPool(g, t).isNotEmpty() }

    /** Other slots' groups linked into this one. */
    fun linkedInto(t: Target) = groups.filter { t in it.links && it.target != t && !it.isUnassigned }

    /** Every group that can be active on a slot, in priority order: other slots' groups linked here, then its own (in the user's order), then global ones. */
    fun groupsOn(t: Target) = groups.filter { !it.isUnassigned && (it.target == t || t in it.links || it.global) }
        .sortedBy { if (t in it.links) 0 else if (it.target == t) 1 else 2 }

    /** True when [w] can show on [t]: its own slot, or a slot one of its groups is linked to. */
    fun canShow(w: Wallpaper, t: Target) = w.target == t || groups.any { t in it.links && it.target == w.target && w.id in it.members }
    fun unassigned(t: Target) = groups.firstOrNull { it.id == Group.unassignedId(t) } ?: Group.unassigned(t)
    fun group(id: String?) = groups.firstOrNull { it.id == id } ?: Target.entries.firstOrNull { Group.unassignedId(it) == id }?.let(::unassigned)

    /** The user groups an image is in (empty = it is in the slot's unassigned group). */
    fun groupsOf(w: Wallpaper): List<Group> = groupsFor(w.target).filter { w.id in it.members }

    /**
     * The images of a group in order. Unassigned: the slot's images in no user group, ordered by
     * its [Group.members] first, then in library order.
     */
    fun inGroup(g: Group, t: Target = g.target): List<Wallpaper> {
        val slot = contentSlot(g, t)
        if (!g.isUnassigned) return g.members.distinct().mapNotNull { id -> byId(id)?.takeIf { it.target == slot } }
        val grouped = groupsFor(g.target).flatMap { it.members }.toSet()
        val loose = forTarget(g.target).filter { it.id !in grouped }
        val order = g.members.withIndex().associate { (i, id) -> id to i }
        return loose.withIndex().sortedBy { (i, w) -> order[w.id] ?: (order.size + i) }.map { it.value }
    }

    /** The images Automatic and Next choose from: the group's images minus the ones it skips. */
    fun autoPool(g: Group, t: Target = g.target) = inGroup(g, t).filter { it.id !in g.skipped }

    fun setSkipped(groupId: String, wallpaperId: String, skip: Boolean) = withGroup(groupId) { g ->
        g.copy(skipped = if (skip) g.skipped + wallpaperId else g.skipped - wallpaperId)
    }

    /** Adds an image to a group (at [index], else at the end); a no-op if it is already there. */
    fun addToGroup(groupId: String, wallpaperId: String, index: Int? = null) = withGroup(groupId) { g ->
        if (wallpaperId in g.members || g.isUnassigned) g
        else g.copy(members = g.members.toMutableList().apply { add((index ?: size).coerceIn(0, size), wallpaperId) })
    }

    fun removeFromGroup(groupId: String, wallpaperId: String) = withGroup(groupId) { g -> g.copy(members = g.members - wallpaperId, skipped = g.skipped - wallpaperId) }

    /** Removes an image from every group (used when it is deleted). */
    fun withoutWallpaper(id: String) = copy(
        wallpapers = wallpapers.filter { it.id != id },
        groups = groups.map { if (id in it.members || id in it.skipped) it.copy(members = it.members - id, skipped = it.skipped - id) else it },
        pins = pins.filterValues { it != id },
    )
    fun rulesFor(g: Group) = g.auto ?: autoDefaults
    /** True when some slot with Automatic on advances on screen off or fold/unfold (the screen watcher must run). */
    fun needsScreenWatch() = autoOn.isNotEmpty() &&
        (autoDefaults.needsWatcher || groups.any { g -> (g.global || g.targets.any { it in autoOn }) && g.auto?.needsWatcher == true })

    fun activeGroupOf(t: Target) = group(activeGroup[t])?.takeIf { t in it.targets || it.global } ?: unassigned(t)

    /** Replaces (or first stores) a group, e.g. an unassigned group's settings. */
    fun withGroup(id: String, f: (Group) -> Group): LibraryState {
        val g = group(id) ?: return this
        return if (groups.any { it.id == id }) copy(groups = groups.map { if (it.id == id) f(it) else it })
        else copy(groups = groups + f(g))
    }

    /** A group by id, command name or display name (case-insensitive). */
    fun findGroup(nameOrId: String, target: Target? = null): Group? {
        val pool = Target.entries.filter { target == null || it == target }.flatMap { groupsFor(it) + unassigned(it) }.distinctBy { it.id }
        return pool.firstOrNull { it.id == nameOrId }
            ?: pool.firstOrNull { it.command.equals(nameOrId, true) }
            ?: pool.firstOrNull { it.name.equals(nameOrId, true) }
    }

    /** The group pinned on a slot ("set <group>"), if the pin is a group. */
    fun pinnedGroup(t: Target) = pins[t]?.takeIf { it.startsWith(Json.GROUP_PIN) }?.let { group(it.removePrefix(Json.GROUP_PIN)) }

    /** What the slot's pin holds, for banners: a wallpaper or a group name. */
    fun pinLabel(t: Target): String? = pinnedGroup(t)?.let { "group “${it.name}”" } ?: byId(pins[t])?.let { "“${it.name}”" }

    /** Matches a command name, then a display name, case-insensitively. */
    fun find(nameOrId: String, target: Target? = null): Wallpaper? {
        val pool = if (target == null) wallpapers else forTarget(target)
        return pool.firstOrNull { it.id == nameOrId }
            ?: pool.firstOrNull { it.command.equals(nameOrId, true) }
            ?: pool.firstOrNull { it.name.equals(nameOrId, true) }
    }
}

/** Single JSON file in app storage, mirrored into a StateFlow for the UI. */
object Library {
    private lateinit var file: File
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> get() = _state

    @Synchronized
    fun init(c: Context) {
        if (::file.isInitialized) return
        file = File(c.applicationContext.filesDir, "library.json")
        _state.value = if (file.isFile) runCatching { decode(JSONObject(file.readText())) }.getOrElse { LibraryState() } else LibraryState()
    }

    fun get(c: Context): LibraryState {
        init(c)
        return _state.value
    }

    @Synchronized
    fun update(c: Context, transform: (LibraryState) -> LibraryState): LibraryState {
        init(c)
        val prev = _state.value
        val next = transform(prev)
        // Nothing changed: no write, no StateFlow emission, no widget or Routines refresh.
        if (next == prev) return prev
        _state.value = next
        scheduleWrite()
        // Apps with remote access watch Deskmat's provider URIs.
        RemoteProvider.notifyChanged(c, prev, next)
        // Routines conditions watch these URIs while enabled.
        if (prev.flags != next.flags) RoutineParams.notifyCondition(c, RoutineParams.CONDITION_FLAG)
        if (prev.status.mapValues { it.value.wallpaperId } != next.status.mapValues { it.value.wallpaperId }) {
            RoutineParams.notifyCondition(c, RoutineParams.CONDITION_SHOWING)
        }
        // Widgets show the active group's mode (dice for shuffle, fast-forward for in order).
        if (prev.groups != next.groups || prev.activeGroup != next.activeGroup || prev.pins != next.pins ||
            prev.widgetPadding != next.widgetPadding || prev.picks != next.picks
        ) {
            Widgets.refresh(c)
        }
        return next
    }

    private val writer = Executors.newSingleThreadExecutor()
    private val writePending = AtomicBoolean(false)

    /**
     * Saves on a background thread, off whoever changed the library (often the UI). Changes that
     * arrive before the save starts share it; the save always writes the latest state.
     */
    private fun scheduleWrite() {
        if (!writePending.compareAndSet(false, true)) return
        writer.execute {
            writePending.set(false)
            val tmp = File(file.parentFile, "library.json.tmp")
            tmp.writeText(encode(_state.value).toString(2))
            tmp.renameTo(file)
        }
    }

    fun imagesDir(c: Context) = File(c.applicationContext.filesDir, "images").apply { mkdirs() }

    private fun encode(s: LibraryState) = JSONObject()
        .put("version", 3)
        .put("wallpapers", JSONArray(s.wallpapers.map(Json::wallpaper)))
        .put("groups", JSONArray(s.groups.map(Json::group)))
        .put("flags", JSONObject(s.flags as Map<*, *>))
        .put("pins", JSONObject(s.pins.mapKeys { it.key.key } as Map<*, *>))
        .put("status", JSONObject(s.status.mapKeys { it.key.key }.mapValues { (_, v) ->
            JSONObject().put("id", v.wallpaperId).put("reason", v.reason).put("at", v.at)
        } as Map<*, *>))
        .put("activeGroup", JSONObject(s.activeGroup.mapKeys { it.key.key } as Map<*, *>))
        .put("aspect", s.aspect)
        .put("lastDexAspect", s.lastDexAspect.toDouble())
        .put("lastAppliedKey", JSONObject(s.lastAppliedKey.mapKeys { it.key.key } as Map<*, *>))
        .put("inside", JSONArray(s.inside.toList()))
        .put("geofencing", s.geofencing)
        .put("geofenceStatus", s.geofenceStatus)
        .put("autoOn", JSONArray(s.autoOn.map { it.key }))
        .put("autoDefaults", Json.auto(s.autoDefaults))
        .put("picks", JSONObject(s.picks as Map<*, *>))
        .put("queued", JSONObject(s.queued as Map<*, *>))
        .put("changedAt", JSONObject(s.changedAt.mapKeys { it.key.key } as Map<*, *>))
        .put("advanceDue", JSONArray(s.advanceDue.map { it.key }))
        .put("advanceManual", JSONArray(s.advanceManual.map { it.key }))
        .put("pendingHidden", JSONObject(s.pendingHidden.mapKeys { it.key.key } as Map<*, *>))
        .put("dexActive", s.dexActive)
        .put("dexPending", JSONArray(s.dexPending.map { it.key }))
        .put("shortcutFlags", JSONArray(s.shortcutFlags.toList()))
        .put("pinBase", JSONObject(s.pinBase.mapKeys { it.key.key } as Map<*, *>))
        .put("themeFromImage", s.themeFromImage)
        .put("downsample", s.downsample)
        .put("saveUpscaled", s.saveUpscaled)
        .put("lastDexWidth", s.lastDexWidth)
        .put("rotating", JSONArray(s.rotating.map { it.key }))
        .put("widgetPadding", JSONObject(s.widgetPadding.mapKeys { it.key.key } as Map<*, *>))

    private fun decode(o: JSONObject): LibraryState {
        val wps = o.optJSONArray("wallpapers") ?: JSONArray()
        val s = LibraryState(
            wallpapers = (0 until wps.length()).map { Json.wallpaper(wps.getJSONObject(it)) },
            groups = o.optJSONArray("groups")?.let { a -> (0 until a.length()).map { Json.group(a.getJSONObject(it)) } } ?: emptyList(),
            flags = o.optJSONObject("flags").toMap { k, v -> k to (v as Boolean) },
            pins = o.optJSONObject("pins").toMap { k, v -> Target.of(k)!! to v as String },
            status = o.optJSONObject("status").toMap { k, v ->
                val j = v as JSONObject
                Target.of(k)!! to SlotStatus(j.optString("id").ifBlank { null }, j.optString("reason"), j.optLong("at"))
            },
            activeGroup = o.optJSONObject("activeGroup").toMap { k, v -> Target.of(k)!! to v as String },
            aspect = o.optString("aspect", "auto"),
            lastDexAspect = o.optDouble("lastDexAspect", 1.6).toFloat(),
            lastAppliedKey = o.optJSONObject("lastAppliedKey").toMap { k, v -> Target.of(k)!! to v as String },
            inside = o.optJSONArray("inside")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet(),
            geofencing = o.optBoolean("geofencing"),
            geofenceStatus = o.optString("geofenceStatus", "Not set up"),
            autoOn = targets(o.optJSONArray("autoOn") ?: o.optJSONArray("shuffleOn")),
            autoDefaults = o.optJSONObject("autoDefaults")?.let(Json::auto) ?: AutoRules(
                onDexEnd = o.optBoolean("shuffleOnDisconnect", true),
                interval = o.optBoolean("shuffleInterval"),
                intervalMin = o.optInt("shuffleIntervalMin", 60),
            ),
            picks = o.optJSONObject("picks").toMap { k, v -> k to v as String },
            queued = o.optJSONObject("queued").toMap { k, v -> k to v as String },
            changedAt = (o.optJSONObject("changedAt") ?: o.optJSONObject("shuffleAt")).toMap { k, v -> Target.of(k)!! to (v as Number).toLong() },
            advanceDue = targets(o.optJSONArray("advanceDue") ?: o.optJSONArray("shuffleDue")),
            advanceManual = targets(o.optJSONArray("advanceManual")),
            pendingHidden = o.optJSONObject("pendingHidden").toMap { k, v -> Target.of(k)!! to (v as Number).toLong() },
            dexActive = o.optBoolean("dexActive"),
            dexPending = targets(o.optJSONArray("dexPending")),
            themeFromImage = o.optBoolean("themeFromImage", true),
            downsample = o.optString("downsample", "dex"),
            saveUpscaled = o.optBoolean("saveUpscaled"),
            lastDexWidth = o.optInt("lastDexWidth", 1920),
            rotating = o.optJSONArray("rotating")?.let(::targets) ?: setOf(Target.MAIN),
            widgetPadding = o.optJSONObject("widgetPadding").toMap { k, v -> WidgetStyle.entries.first { it.key == k } to (v as Number).toInt() },
            pinBase = o.optJSONObject("pinBase").toMap { k, v -> Target.of(k)!! to v as String },
            shortcutFlags = o.optJSONArray("shortcutFlags")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet(),
        )
        val v = o.optInt("version")
        // Up to version 2 each image named its one group ("group"); groups now list their images.
        val legacy = (0 until wps.length()).associate { wps.getJSONObject(it).getString("id") to wps.getJSONObject(it).optString("group") }
            .filterValues { it.isNotBlank() }
        val migrated = if (v < 2) migrate(s, wps) else s
        return if (v < 3) withLegacyMembers(migrated, legacy) else migrated
    }

    private fun withLegacyMembers(s: LibraryState, legacy: Map<String, String>): LibraryState {
        val byGroup = s.wallpapers.mapNotNull { w -> legacy[w.id]?.let { it to w.id } }.groupBy({ it.first }, { it.second })
        return s.copy(groups = s.groups.map { g -> byGroup[g.id]?.let { g.copy(members = g.members + it.filter { id -> id !in g.members }) } ?: g })
    }

    /**
     * Version 0.17 kept triggers, "default" and "include in shuffle" on each image. Images with
     * the same triggers become one group (named after its first image); the default image moves
     * to the front of its unassigned group. Shuffle stays on where it was on for the slot.
     */
    private fun migrate(s: LibraryState, wps: JSONArray): LibraryState {
        val groups = mutableListOf<Group>()
        s.wallpapers.forEachIndexed { i, w ->
            val triggers = Json.triggers(wps.getJSONObject(i).optJSONArray("groups")).filter { it.conditions.isNotEmpty() }
            if (triggers.isEmpty()) return@forEachIndexed
            val gi = groups.indexOfFirst { it.target == w.target && it.triggers == triggers }
            if (gi < 0) groups += Group("g_${w.id}", w.target, w.name, triggers, members = listOf(w.id))
            else groups[gi] = groups[gi].copy(members = groups[gi].members + w.id)
        }
        val wallpapers = s.wallpapers
        val defaults = (0 until wps.length()).filter { wps.getJSONObject(it).optBoolean("default") }.map { s.wallpapers[it].id }.toSet()
        val shuffled = Target.entries.filter { it in s.autoOn }.map { Group.unassigned(it).copy(shuffle = true) }
        return s.copy(
            wallpapers = wallpapers.sortedBy { if (it.id in defaults) 0 else 1 },
            groups = groups.map { if (it.target in s.autoOn) it.copy(shuffle = true) else it } + shuffled,
        )
    }

    private fun targets(a: JSONArray?): Set<Target> =
        if (a == null) emptySet() else (0 until a.length()).mapNotNull { Target.of(a.getString(it)) }.toSet()

    private fun <K, V> JSONObject?.toMap(f: (String, Any) -> Pair<K, V>): Map<K, V> {
        if (this == null) return emptyMap()
        return keys().asSequence().mapNotNull { k -> runCatching { f(k, get(k)) }.getOrNull() }.toMap()
    }
}
