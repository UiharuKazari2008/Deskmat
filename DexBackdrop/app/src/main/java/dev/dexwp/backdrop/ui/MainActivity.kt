package dev.dexwp.backdrop.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.dexwp.backdrop.data.Library
import dev.dexwp.backdrop.engine.Applier
import dev.dexwp.backdrop.engine.Commands
import dev.dexwp.backdrop.engine.Engine
import dev.dexwp.backdrop.engine.Upscaler
import dev.dexwp.backdrop.engine.Images
import dev.dexwp.backdrop.engine.Locator
import dev.dexwp.backdrop.engine.Screens
import dev.dexwp.backdrop.engine.Seen
import dev.dexwp.backdrop.model.Group
import dev.dexwp.backdrop.model.Json
import dev.dexwp.backdrop.widget.Widgets
import dev.dexwp.backdrop.model.Target
import dev.dexwp.backdrop.model.Wallpaper
import dev.dexwp.backdrop.system.Geofences
import dev.dexwp.backdrop.system.ScreenWatch
import dev.dexwp.backdrop.system.Scheduler
import dev.dexwp.backdrop.system.Shortcuts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private sealed interface Page {
    data object Gallery : Page
    /** [view]: the slot the image is seen from, when that is another screen showing it through a linked group. */
    data class Detail(val id: String, val view: Target? = null, val seq: List<String> = emptyList(), val seqName: String? = null) : Page
    data class CropEdit(val id: String, val landscape: Boolean = false, val view: Target? = null) : Page
    /** Newly added images, cropped one after another; [views] gives the slot of each step (linked groups crop each screen). */
    data class CropBatch(
        val ids: List<String>, val index: Int, val views: List<Target?> = ids.map { null },
        /** Steps that crop the landscape view (screens that rotate get one after the portrait crop). */
        val lands: List<Boolean> = ids.map { false },
    ) : Page
    data class GroupEdit(val id: String) : Page
    data object Flags : Page
    data object Settings : Page
}

class MainActivity : ComponentActivity() {
    /** Bumped on every resume so permission state is re-read after returning from Settings. */
    private val resumes = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Library.init(this)
        Scheduler.ensureJob(this)
        setContent { App(resumes.intValue, window) }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Hidden or short of memory: the preview cache can be decoded again when needed.
        if (level >= TRIM_MEMORY_UI_HIDDEN) Images.trim()
    }

    override fun onResume() {
        super.onResume()
        resumes.intValue++
    }
}

@Composable
private fun App(resumeCount: Int, window: android.view.Window) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Library.state.collectAsState()
    // Last screen shown is remembered across launches; the main screen the first time.
    val prefs = remember { ctx.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    var tab by rememberSaveable { mutableStateOf(Target.of(prefs.getString("tab", null)) ?: Target.MAIN) }
    LaunchedEffect(tab) { prefs.edit().putString("tab", tab.key).apply() }
    val screens = remember { Screens.available(ctx) }
    val foldable = remember { Screens.foldable(ctx) }
    if (tab.screen !in screens) tab = Target.MAIN
    val stack = remember { mutableStateListOf<Page>(Page.Gallery) }
    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
    /** Images being upscaled (file path → progress 0–1). */
    val upscaling = remember { mutableStateMapOf<String, Float>() }
    fun upscale(wp: Wallpaper) {
        if (wp.file in upscaling) return
        upscaling[wp.file] = 0f
        scope.launch(Dispatchers.Default) {
            val r = Upscaler.upscale(ctx, wp) { done, total -> upscaling[wp.file] = done.toFloat() / total; true }
            withContext(Dispatchers.Main) {
                upscaling.remove(wp.file)
                r.onSuccess { saved -> toast("Upscaled ${wp.name}" + (saved?.let { " · saved to ${it.removePrefix("/storage/emulated/0/")}" } ?: "")); Engine.runAsync(ctx, "upscale") }
                    .onFailure { toast("Upscale failed: ${it.message}") }
            }
        }
    }
    fun go(s: Page) = stack.add(s)
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    BackHandler(enabled = stack.size > 1) { back() }

    // Any gallery edit: refresh shortcuts and re-evaluate (debounced so typing doesn't thrash).
    // Start or stop the screen-off watcher while the app is in front (Android only lets a visible app start it).
    LaunchedEffect(state.autoOn, state.autoDefaults, state.groups) { ScreenWatch.sync(ctx) }

    LaunchedEffect(state.wallpapers, state.groups, state.shortcutFlags) {
        delay(700)
        withContext(Dispatchers.IO) { Shortcuts.publish(ctx); Geofences.sync(ctx) }
        Engine.runAsync(ctx, "edit")
    }

    fun update(id: String, f: (Wallpaper) -> Wallpaper) {
        Library.update(ctx) { s -> s.copy(wallpapers = s.wallpapers.map { if (it.id == id) f(it) else it }) }
    }

    /** Edits the image as slot [t] sees it (that slot's crops when it shows the image through a linked group). */
    fun updateOn(id: String, t: Target, f: (Wallpaper) -> Wallpaper) = update(id) { real -> real.withView(t, f(real.on(t))) }

    fun updateGroup(id: String, f: (Group) -> Group) {
        Library.update(ctx) { it.withGroup(id, f) }
    }

    fun copyTo(wp: Wallpaper, t: Target) {
        val copy = wp.copy(id = UUID.randomUUID().toString(), target = t, command = null).forSlot(t, wp.target)
        Library.update(ctx) { it.copy(wallpapers = it.wallpapers + copy) }
        toast("Copied to the ${t.phrase}")
    }

    fun moveTo(wp: Wallpaper, t: Target) {
        // Leaves its groups (they belong to the old slot); the file stays shared with any copies.
        Library.update(ctx) { s -> s.withoutWallpaper(wp.id).let { it.copy(wallpapers = it.wallpapers + wp.forSlot(t, wp.target).copy(target = t)) } }
        toast("Moved to the ${t.phrase}")
    }

    fun aspectOf(t: Target) = Applier.aspect(ctx, t)
    fun aspectLabel(t: Target) = if (t.isDex) "DeX" else t.screen.short

    /** Group that picked images go to. */
    var addTo by remember { mutableStateOf<Group?>(null) }
    fun importPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val g = addTo ?: Library.get(ctx).unassigned(tab)
        // A global group added to from another screen gets that screen's images.
        val home = Library.get(ctx).contentSlot(g, tab)
        scope.launch {
            val imported = withContext(Dispatchers.IO) { uris.mapNotNull { uri -> runCatching { Images.importUri(ctx, uri) }.getOrNull() } }
            if (imported.size < uris.size) toast("${uris.size - imported.size} of ${uris.size} images could not be imported")
            if (imported.isEmpty()) return@launch
            val base = Library.get(ctx).forTarget(home).size
            val wps = imported.mapIndexed { i, imp ->
                Wallpaper(UUID.randomUUID().toString(), home, "${home.label} ${base + i + 1}", imp.file.path, imp.width, imp.height, source = imp.source)
            }
            Library.update(ctx) { s -> wps.fold(s.copy(wallpapers = s.wallpapers + wps)) { acc, w -> acc.addToGroup(g.id, w.id) } }
            // A linked group: each image is cropped for every screen it drives, its own first.
            val slots = if (home != g.target) listOf(home) else listOf(g.target) + g.links.filter { it.screen in screens }
            // Screens that turn sideways also get a landscape crop, right after the portrait one.
            val rotating = Library.get(ctx).rotating
            val steps = wps.flatMap { w -> slots.flatMap { t -> listOf(Triple(w.id, t, false)) + if (t in rotating) listOf(Triple(w.id, t, true)) else emptyList() } }
            go(Page.CropBatch(steps.map { it.first }, 0, steps.map { it.second }, steps.map { it.third }))
        }
    }
    // Samsung Gallery's own multi-picker, with the system photo picker as fallback.
    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data?.takeIf { r.resultCode == android.app.Activity.RESULT_OK } ?: return@rememberLauncherForActivityResult
        importPicked(d.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } } ?: listOfNotNull(d.data))
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris -> importPicked(uris) }
    fun pickImages(g: Group) {
        addTo = g
        val gallery = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).setPackage("com.sec.android.gallery3d")
        if (gallery.resolveActivity(ctx.packageManager) != null) galleryPicker.launch(gallery)
        else photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val locationPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { Geofences.sync(ctx); Engine.runAsync(ctx, "manual") }
    val backgroundPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Geofences.sync(ctx); Engine.runAsync(ctx, "manual") }
    fun openAllFiles() = ctx.startActivity(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")),
    )

    fun setNow(wp: Wallpaper) {
        // wp may be the view from a linked screen: its target is that screen.
        Library.update(ctx) { it.copy(pins = it.pins + (wp.target to wp.id)) }
        Engine.runAsync(ctx, "manual", force = true) { scope.launch { toast("Set “${wp.name}” on the ${wp.target.phrase}") } }
    }

    // Theme seed: the image being edited, else what this tab shows now.
    val seedWp = when (val top = stack.last()) {
        is Page.Detail -> state.byId(top.id)
        is Page.CropEdit -> state.byId(top.id)
        is Page.CropBatch -> state.byId(top.ids.getOrNull(top.index))
        else -> state.byId(state.status[tab]?.wallpaperId)
    }?.takeIf { state.themeFromImage }
    val seed by produceState<Int?>(null, seedWp?.file, seedWp?.crop) {
        value = seedWp?.let { w -> withContext(Dispatchers.IO) { Images.seedColor(w.file, w.crop) } }
    }
    val dark = isSystemInDarkTheme()
    val cap = rememberCaption()
    // Desktop windows: transparent, pass-through caption bar while our header lives in it.
    SideEffect {
        if (android.os.Build.VERSION.SDK_INT < 35) return@SideEffect
        val c = WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND
        val l = WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS
        window.insetsController?.setSystemBarsAppearance((if (cap.wantTransparent) c else 0) or (if (dark) 0 else l), c or l)
    }

    BackdropTheme(seed?.let { Color(it) }) {
    CompositionLocalProvider(LocalCaption provides cap) {
    when (val screen = stack.last()) {
        Page.Gallery -> GalleryScreen(
            state = state, tab = tab, screens = screens, foldable = foldable, onTab = { tab = it },
            ratio = remember(tab) { aspectOf(tab) },
            onOpen = { w, seq, g -> go(Page.Detail(w.id, seq = seq, seqName = g.name)) },
            onOpenLinked = { w, seq, g -> go(Page.Detail(w.id, tab, seq, g.name)) },
            onAdd = ::pickImages,
            onImportCurrent = {
                if (!Environment.isExternalStorageManager()) {
                    toast("Allow All files access to read the current wallpaper")
                    openAllFiles()
                } else {
                    val target = tab
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { Applier.importCurrent(ctx, target) } }
                            .onSuccess { wp -> Library.update(ctx) { it.copy(wallpapers = it.wallpapers + wp) }; go(Page.Detail(wp.id)) }
                            .onFailure { toast("Import failed: ${it.message}") }
                    }
                }
            },
            onResume = {
                Library.update(ctx) { it.copy(pins = it.pins - tab, pinBase = it.pinBase - tab) }
                Engine.runAsync(ctx, "manual", force = true)
            },
            onRefresh = { Engine.runAsync(ctx, "manual", force = true) { scope.launch { toast("Triggers re-checked") } } },
            onFlags = { go(Page.Flags) },
            onSettings = { go(Page.Settings) },
            onAuto = { on ->
                val target = tab
                Library.update(ctx) { s -> s.copy(autoOn = if (on) s.autoOn + target else s.autoOn - target) }
                Engine.runAsync(ctx, "auto")
            },
            onNext = {
                val target = tab
                Library.update(ctx) {
                    it.copy(advanceDue = it.advanceDue + target, advanceManual = it.advanceManual + target, pins = it.pins - target, pinBase = it.pinBase - target)
                }
                Engine.runAsync(ctx, "next")
            },
            onNewGroup = {
                val g = Group("g_${UUID.randomUUID()}", tab, "Group ${state.groupsFor(tab).size + 1}")
                Library.update(ctx) { it.copy(groups = it.groups + g) }
                go(Page.GroupEdit(g.id))
            },
            onEditGroup = { go(Page.GroupEdit(it.id)) },
            onGroup = ::updateGroup,
            onArrange = { lists ->
                Library.update(ctx) { s ->
                    // Images deleted during the drag are dropped; Unassigned's list only sets its order.
                    // A global group keeps the images it has on other screens.
                    lists.entries.fold(s) { acc, (gid, ids) ->
                        acc.withGroup(gid) { g -> g.copy(members = g.members.filter { id -> acc.byId(id)?.let { it.target != tab } == true } + ids.filter { id -> acc.byId(id) != null }) }
                    }
                }
            },
            onAddToGroup = { gid, wid -> Library.update(ctx) { it.addToGroup(gid, wid) } },
            onRemoveFromGroup = { gid, wid -> Library.update(ctx) { it.removeFromGroup(gid, wid) } },
            onSkip = { gid, wid, skip -> Library.update(ctx) { it.setSkipped(gid, wid, skip) } },
            otherSlots = Target.entries.filter { it != tab && it.screen in screens },
            onCopyTo = ::copyTo,
            onMoveTo = ::moveTo,
            onDropLinked = { gid, wid, copy ->
                val s0 = Library.get(ctx)
                val g = s0.group(gid)
                val wp = s0.byId(wid)
                if (g != null && wp != null && wp.target != g.target) {
                    // The group's images live on its own screen; this image's crop stays this screen's view.
                    val moved = wp.forSlot(g.target, wp.target).copy(target = g.target, id = if (copy) UUID.randomUUID().toString() else wp.id, command = if (copy) null else wp.command)
                    Library.update(ctx) { s ->
                        (if (copy) s else s.withoutWallpaper(wid)).let { it.copy(wallpapers = it.wallpapers + moved) }.addToGroup(gid, moved.id)
                    }
                    toast((if (copy) "Copied" else "Moved") + " to ${g.name} on the ${g.target.phrase}")
                }
            },
            onDropFromLinked = { _, wid, to, copy ->
                val s0 = Library.get(ctx)
                val g = s0.group(to)
                val wp = s0.byId(wid)
                if (g != null && wp != null && wp.target != tab) {
                    // Leaves the linked group's screen (and its groups there) unless copied; this screen's crop stays.
                    val moved = wp.forSlot(tab, wp.target).copy(target = tab, id = if (copy) UUID.randomUUID().toString() else wp.id, command = if (copy) null else wp.command)
                    Library.update(ctx) { s ->
                        (if (copy) s else s.withoutWallpaper(wid)).let { it.copy(wallpapers = it.wallpapers + moved) }
                            .let { if (g.isUnassigned) it else it.addToGroup(to, moved.id) }
                    }
                    toast((if (copy) "Copied" else "Moved") + " to ${g.name} on the ${tab.phrase}")
                }
            },
        )

        is Page.GroupEdit -> {
            val g = state.group(screen.id)
            if (g == null) { LaunchedEffect(Unit) { back() }; return@CompositionLocalProvider }
            val siblings = state.ownGroups(g.target)
            GroupScreen(
                g = g, count = if (g.isUnassigned) state.inGroup(g).size else g.members.distinct().count { state.byId(it) != null },
                active = g.id in state.activeGroup.values,
                knownFlags = state.flags.keys, defaults = state.autoDefaults,
                priority = if (g.isUnassigned) null else siblings.indexOfFirst { it.id == g.id } + 1 to siblings.size,
                onBack = ::back,
                onUpdate = { f -> updateGroup(g.id, f) },
                onMove = { d ->
                    Library.update(ctx) { s ->
                        val mine = s.ownGroups(g.target).toMutableList()
                        val i = mine.indexOfFirst { it.id == g.id }
                        val j = (i + d).coerceIn(0, mine.lastIndex)
                        if (i < 0 || i == j) return@update s
                        mine.add(j, mine.removeAt(i))
                        s.copy(groups = s.groups.filter { it.target != g.target || it.isUnassigned } + mine)
                    }
                },
                showDex = Screens.dexSupported(ctx),
                onPinShortcut = { if (!Shortcuts.requestPin(ctx, g)) toast("Launcher does not support pinned shortcuts") },
                linkable = Target.entries.filter { it.screen in screens && it.screen != g.target.screen },
                onDelete = {
                    Library.update(ctx) { s ->
                        s.copy(
                            groups = s.groups.filter { it.id != g.id },
                            pins = s.pins.filterValues { it != Json.GROUP_PIN + g.id },
                            picks = s.picks.filterKeys { it != g.id && !it.startsWith("${g.id}@") },
                            queued = s.queued.filterKeys { it != g.id && !it.startsWith("${g.id}@") },
                            activeGroup = s.activeGroup.filterValues { it != g.id },
                        )
                    }
                    back()
                },
            )
        }

        is Page.CropBatch -> {
            val real = state.byId(screen.ids.getOrNull(screen.index))
            if (real == null) { LaunchedEffect(Unit) { back() }; return@CompositionLocalProvider }
            val view = screen.views.getOrNull(screen.index) ?: real.target
            val land = screen.lands.getOrNull(screen.index) == true
            val wp = real.on(view)
            val aspect = remember(wp.target) { aspectOf(wp.target) }
            CropScreen(
                wp, if (land) 1f / aspect else aspect, aspectLabel(wp.target) + if (land) " landscape" else "", onCancel = null, onSave = {},
                crop = if (land) wp.cropLand else wp.crop,
                pager = CropPager(screen.index, screen.ids.size) { crop, d ->
                    updateOn(wp.id, view) { if (land) it.copy(cropLand = crop) else it.copy(crop = crop) }
                    val next = screen.index + d
                    if (next >= screen.ids.size) back() else stack[stack.lastIndex] = screen.copy(index = next.coerceAtLeast(0))
                },
            )
        }

        is Page.Detail -> {
            val real = state.byId(screen.id)
            if (real == null) { LaunchedEffect(Unit) { back() }; return@CompositionLocalProvider }
            val view = screen.view?.takeIf { it != real.target }
            val wp = real.on(view ?: real.target)
            // Previous / Next walk the group the image was opened from, in its order.
            val seq = screen.seq.filter { state.byId(it) != null }
            val at = seq.indexOf(real.id)
            fun step(d: Int) { seq.getOrNull(at + d)?.let { stack[stack.lastIndex] = screen.copy(id = it) } }
            DetailScreen(
                stretch = Upscaler.stretch(wp, remember(wp.target) { Upscaler.screenWidth(ctx, wp.target) }, remember(wp.target) { aspectOf(wp.target) }),
                upscaling = upscaling[real.file],
                onUpscale = if (Upscaler.canUpscale(real)) ({ upscale(real) }) else null,
                saveUpscaled = state.saveUpscaled,
                onSaveUpscaled = { on -> Library.update(ctx) { it.copy(saveUpscaled = on) } },
                onSaveCopy = if (real.upscaledFrom != null) ({
                    scope.launch(Dispatchers.IO) {
                        val r = Upscaler.saveCopy(ctx, real)
                        withContext(Dispatchers.Main) {
                            r.onSuccess { toast("Saved to ${it.removePrefix("/storage/emulated/0/")}") }.onFailure { toast("Couldn't save: ${it.message}") }
                        }
                    }
                }) else null,
                position = if (at >= 0 && seq.size > 1) Triple(at + 1, seq.size, screen.seqName ?: "") else null,
                onPrevious = { step(-1) },
                onNext = { step(1) },
                linkedFrom = view?.let { real.target },
                wp = wp, inGroups = state.groupsOf(real).map { it.id }.toSet(), groups = state.groupsFor(real.target),
                onToggleGroup = { g, on -> Library.update(ctx) { if (on) it.addToGroup(g.id, wp.id) else it.removeFromGroup(g.id, wp.id) } },
                autoGroups = (state.groupsOf(real).ifEmpty { listOf(state.unassigned(real.target)) }).map { it to (wp.id !in it.skipped) },
                onSkip = { g, skip -> Library.update(ctx) { it.setSkipped(g.id, wp.id, skip) } },
                otherSlots = if (view != null) emptyList() else Target.entries.filter { it != wp.target && it.screen in screens },
                onCopyTo = { t -> copyTo(wp, t) },
                onMoveTo = { t -> moveTo(wp, t) },
                showing = state.status[wp.target]?.wallpaperId == wp.id,
                onBack = ::back,
                onUpdate = { f -> updateOn(wp.id, wp.target, f) },
                onSetNow = { setNow(wp) },
                pinned = state.pins[wp.target] == wp.id,
                onUnpin = {
                    Library.update(ctx) { it.copy(pins = it.pins - wp.target, pinBase = it.pinBase - wp.target) }
                    Engine.runAsync(ctx, "manual", force = true)
                },
                onEditCrop = { land -> go(Page.CropEdit(wp.id, land, view)) },
                aspect = remember(wp.target) { aspectOf(wp.target) },
                aspectLabel = aspectLabel(wp.target),
                onSaveCrop = { crop, land -> updateOn(wp.id, wp.target) { if (land) it.copy(cropLand = crop) else it.copy(crop = crop) } },
                hasLandscape = wp.target in state.rotating,
                onPinShortcut = { if (!Shortcuts.requestPin(ctx, wp)) toast("Launcher does not support pinned shortcuts") },
                onDelete = {
                    // In a group: go on to the next image (or the previous at the end) instead of back.
                    val next = seq.getOrNull(at + 1) ?: seq.getOrNull(at - 1)
                    Library.update(ctx) { it.withoutWallpaper(wp.id) }
                    Images.deleteIfUnused(ctx, wp.file)
                    if (at >= 0 && next != null) stack[stack.lastIndex] = screen.copy(id = next) else back()
                },
            )
        }

        is Page.CropEdit -> {
            val real = state.byId(screen.id)
            if (real == null) { LaunchedEffect(Unit) { back() }; return@CompositionLocalProvider }
            val view = screen.view ?: real.target
            val wp = real.on(view)
            val aspect = remember(wp.target) { aspectOf(wp.target) }
            if (screen.landscape) CropScreen(
                wp, 1f / aspect, "${aspectLabel(wp.target)} landscape", onCancel = ::back, crop = wp.cropLand,
                onSave = { crop -> updateOn(wp.id, view) { it.copy(cropLand = crop) }; back() },
            )
            else CropScreen(wp, aspect, aspectLabel(wp.target), onCancel = ::back, onSave = { crop -> updateOn(wp.id, view) { it.copy(crop = crop) }; back() })
        }

        Page.Flags -> FlagsScreen(
            state = state, onBack = ::back,
            onSet = { name, on -> scope.launch(Dispatchers.IO) { Commands.execute(ctx, "flag", name, null, if (on) "on" else "off") } },
            onDelete = { name -> Library.update(ctx) { it.copy(flags = it.flags - name, shortcutFlags = it.shortcutFlags - name) } },
            onShortcut = { name, on ->
                Library.update(ctx) { it.copy(shortcutFlags = if (on) it.shortcutFlags + name else it.shortcutFlags - name) }
            },
        )

        Page.Settings -> {
            LaunchedEffect(resumeCount) { withContext(Dispatchers.IO) { Geofences.sync(ctx) } }
            val perms = remember(resumeCount, state) {
                PermissionInfo(Locator.hasPermission(ctx), Locator.hasBackground(ctx), Environment.isExternalStorageManager(), Seen.hasAccess(ctx))
            }
            SettingsScreen(
                state = state, perms = perms, onBack = ::back,
                onAspect = { a -> Library.update(ctx) { it.copy(aspect = a) } },
                onLocation = { locationPerm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                onBackground = {
                    if (!Locator.hasPermission(ctx)) toast("Allow location first")
                    else backgroundPerm.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                },
                onAllFiles = ::openAllFiles,
                onThemeFromImage = { on -> Library.update(ctx) { it.copy(themeFromImage = on) } },
                onDownsample = { d ->
                    Library.update(ctx) { it.copy(downsample = d) }
                    Engine.runAsync(ctx, "settings")
                },
                dex = Screens.dexSupported(ctx),
                onAddWidget = { kind, st ->
                    if (!Widgets.requestPin(ctx, kind, st)) toast("This launcher can't add widgets from apps; add it from the widget list")
                },
                onUsage = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                screens = screens,
                onRotating = { t, on ->
                    Library.update(ctx) { it.copy(rotating = if (on) it.rotating + t else it.rotating - t) }
                    Engine.runAsync(ctx, "settings")
                },
                onWidgetPadding = { st, dp ->
                    Library.update(ctx) { it.copy(widgetPadding = it.widgetPadding + (st to dp)) }
                    Widgets.refresh(ctx)
                },
                onShuffleSettings = { f ->
                    Library.update(ctx, f)
                    Engine.runAsync(ctx, "settings")
                },
                onSaveScript = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { runCatching { saveScript(ctx) } }
                        toast(if (ok.isSuccess) "Saved dexwp to Downloads" else "Save failed: ${ok.exceptionOrNull()?.message}")
                    }
                },
            )
        }
    }
    }
    }
}

/**
 * Keeps the crops when only home/lock changes (same screen shape); otherwise the new screen's own
 * crop from a linked group is used, if one was set.
 */
private fun Wallpaper.forSlot(to: Target, from: Target) =
    if (to.screen == from.screen) this else copy(crop = crops[to], cropLand = cropsLand[to], crops = crops - to, cropsLand = cropsLand - to)

/** Writes assets/dexwp to Downloads/dexwp via MediaStore (no storage permission needed). */
private fun saveScript(c: Context) {
    val resolver = c.contentResolver
    val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
    resolver.delete(collection, "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf("dexwp"))
    val uri = resolver.insert(collection, ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "dexwp")
        put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
    }) ?: error("cannot create file")
    resolver.openOutputStream(uri)!!.use { out -> c.assets.open("dexwp").use { it.copyTo(out) } }
}
