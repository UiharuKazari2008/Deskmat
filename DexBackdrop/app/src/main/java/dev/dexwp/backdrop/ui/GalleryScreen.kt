package dev.dexwp.backdrop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import dev.dexwp.backdrop.data.LibraryState
import dev.dexwp.backdrop.engine.Rules
import dev.dexwp.backdrop.engine.Upscaler
import dev.dexwp.backdrop.model.Group
import dev.dexwp.backdrop.model.Screen
import dev.dexwp.backdrop.model.Target
import dev.dexwp.backdrop.model.Wallpaper

fun screenIcon(s: Screen, foldable: Boolean): ImageVector = when (s) {
    Screen.DESKTOP -> DesktopIcon
    Screen.MAIN -> if (foldable) FoldIcon else PhoneIcon
    Screen.COVER -> PhoneIcon
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    state: LibraryState,
    tab: Target,
    screens: List<Screen>,
    foldable: Boolean,
    /** Width/height of the selected screen: previews use its shape. */
    ratio: Float,
    onTab: (Target) -> Unit,
    /** Opens an image; the list is its group's images in order (for Previous / Next) and the group. */
    onOpen: (Wallpaper, List<String>, Group) -> Unit,
    /** Opens an image of another screen's linked group, with this screen's crop. */
    onOpenLinked: (Wallpaper, List<String>, Group) -> Unit,
    onAdd: (Group) -> Unit,
    onImportCurrent: () -> Unit,
    onResume: () -> Unit,
    onRefresh: () -> Unit,
    onFlags: () -> Unit,
    onSettings: () -> Unit,
    onAuto: (Boolean) -> Unit,
    onNext: () -> Unit,
    onNewGroup: () -> Unit,
    onEditGroup: (Group) -> Unit,
    onGroup: (String, (Group) -> Group) -> Unit,
    /** New member lists (group id → wallpaper ids, in order) for this slot's groups after a drag. */
    onArrange: (Map<String, List<String>>) -> Unit,
    onAddToGroup: (groupId: String, wallpaperId: String) -> Unit,
    onRemoveFromGroup: (groupId: String, wallpaperId: String) -> Unit,
    onSkip: (groupId: String, wallpaperId: String, skip: Boolean) -> Unit,
    /** Slots an image can be copied or moved to from its menu (the other displays, and home/lock). */
    otherSlots: List<Target>,
    onCopyTo: (Wallpaper, Target) -> Unit,
    onMoveTo: (Wallpaper, Target) -> Unit,
    /** An image dropped on another screen's group linked here: moved (or with Ctrl copied) to that screen and added. */
    onDropLinked: (groupId: String, wallpaperId: String, copy: Boolean) -> Unit,
    /** An image dragged out of a linked group onto one of this screen's groups: moved (Ctrl: copied) here and added. */
    onDropFromLinked: (fromGroup: String, wallpaperId: String, toGroup: String, copy: Boolean) -> Unit,
) {
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()
    var menu by remember { mutableStateOf(false) }
    val status = state.status[tab]
    val showingId = status?.wallpaperId
    val cap = LocalCaption.current
    val screenLabel = { s: Screen -> if (s == Screen.MAIN && !foldable) "Phone" else s.short }
    // Phones with one screen (no DeX, not foldable) have nothing to switch between.
    val multiScreen = screens.size > 1
    val stage = if (cap.integrated) rememberHeaderStage(cap, "Add images", if (multiScreen) screens.map(screenLabel) else emptyList()) else HeaderStage.NO_ADD
    val switcherInCaption = cap.integrated && stage <= HeaderStage.ICON_SWITCHER
    val addInCaption = cap.integrated && stage <= HeaderStage.NO_SWITCHER
    val unassigned = state.unassigned(tab)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val screenW = remember(tab) { Upscaler.screenWidth(ctx, tab) }
    fun lowRes(wp: Wallpaper) = Upscaler.stretch(wp.on(tab), screenW, ratio) >= Upscaler.LOW_RES
    val groups = listOf(unassigned) + state.groupsFor(tab)
    val linkedIn = state.linkedInto(tab)
    val activeId = state.activeGroup[tab]?.takeIf { id -> (groups + linkedIn).any { it.id == id && state.inGroup(it, tab).isNotEmpty() } }

    // ---- Hold and drag to reorder an image or move it to another group (Ctrl+drag adds a copy) ----
    val grid = rememberLazyGridState()
    val haptic = LocalHapticFeedback.current
    val latest by rememberUpdatedState(state)
    val arrange by rememberUpdatedState(onArrange)
    val groupUpdate by rememberUpdatedState(onGroup)
    val dropLinked by rememberUpdatedState(onDropLinked)
    /** Linked group (another screen's) the dragged image is over; it joins that group on drop. */
    var linkTarget by remember { mutableStateOf<String?>(null) }
    val dropFromLinked by rememberUpdatedState(onDropFromLinked)
    /** True while the dragged image comes from a linked group (another screen's images). */
    var fromLinked by remember { mutableStateOf(false) }
    /** That linked group's order while it is being rearranged. */
    var linkOrder by remember { mutableStateOf<List<String>?>(null) }
    /** This screen's group the linked image is over; it joins that group on drop. */
    var ownTarget by remember { mutableStateOf<String?>(null) }
    fun members(s: LibraryState) = (listOf(s.unassigned(tab)) + s.groupsFor(tab)).associate { g -> g.id to s.inGroup(g, tab).map { it.id } }
    /** Each group's image ids while a drag is in progress (committed on drop). */
    var working by remember(tab) { mutableStateOf<Map<String, List<String>>?>(null) }
    /** The dragged entry: group id and wallpaper id. */
    var drag by remember { mutableStateOf<Pair<String, String>?>(null) }
    var origin by remember { mutableStateOf<String?>(null) }
    var copyMode by remember { mutableStateOf(false) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    /** Card whose right-click menu is open ("groupId|wallpaperId"). */
    var cardMenu by remember { mutableStateOf<String?>(null) }
    /** Where the card menu opens: at the pointer, relative to the card's bottom-left corner. */
    var menuOffset by remember { mutableStateOf(DpOffset.Zero) }
    /** Second level of the card menu: "copy" or "move" lists the destinations. */
    var cardMenuMode by remember { mutableStateOf<String?>(null) }
    /** Collapsed groups opened by dropping into them. */
    val opened = remember { mutableStateListOf<String>() }
    val lists = working ?: members(state)
    // Keep the local order until the library has caught up, so the drop doesn't flicker back.
    LaunchedEffect(state.wallpapers, state.groups) { if (drag == null) working = null }

    fun hoverLinked(pos: Offset) {
        val (dg, dw) = drag ?: return
        val key = grid.itemAt(pos)?.key as? String ?: return
        when {
            key.startsWith("lnk:") || key.startsWith("lgroup:") -> {
                ownTarget = null
                val parts = key.substringAfter(':').split("|")
                val tg = parts[0]
                val over = parts.getOrNull(1)
                val order = linkOrder ?: return
                if (tg != dg || over == null || over == dw) return
                linkOrder = order.toMutableList().apply { remove(dw); add(order.indexOf(over), dw) }
            }
            key.startsWith("wp:") || key.startsWith("group:") || key.startsWith("empty:") ->
                ownTarget = key.substringAfter(':').substringBefore('|')
        }
    }

    fun hoverAt(pos: Offset) {
        if (fromLinked) return hoverLinked(pos)
        val (dg, dw) = drag ?: return
        val now = working ?: return
        val key = grid.itemAt(pos)?.key as? String ?: return
        // Over a linked group: no live preview, the image moves to that group's screen on drop.
        if (key.startsWith("lgroup:") || key.startsWith("lnk:")) {
            linkTarget = key.substringAfter(':').substringBefore('|')
            return
        }
        linkTarget = null
        val (tg, over) = when {
            key.startsWith("wp:") -> key.removePrefix("wp:").split("|").let { it[0] to it[1] }
            key.startsWith("group:") || key.startsWith("empty:") -> key.substringAfter(':') to null
            else -> return
        }
        if (tg == dg && over == dw) return
        val target = latest.group(tg) ?: return
        val src = now[dg].orEmpty()
        val dst = now[tg].orEmpty()
        if (tg == dg) {
            if (over == null) return
            working = now + (dg to src.toMutableList().apply { remove(dw); add(src.indexOf(over), dw) })
            return
        }
        if (dw in dst) return
        // Ctrl on the first hop out of a user group keeps the original entry (a copy).
        val copying = copyMode && dg == origin && latest.group(dg)?.isUnassigned == false
        val elsewhere = now.any { (gid, ids) -> gid != dg && gid != tg && latest.group(gid)?.isUnassigned == false && dw in ids }
        // Unassigned only holds images that are in no group.
        if (target.isUnassigned && (copying || elsewhere)) return
        if (target.collapsed && tg !in opened) opened += tg
        val newDst = dst.toMutableList().apply { val i = over?.let { indexOf(it) } ?: -1; if (i >= 0) add(i, dw) else add(dw) }
        working = now + (dg to if (copying) src else src - dw) + (tg to newDst)
        drag = tg to dw
    }

    fun drop() {
        if (fromLinked) {
            val d = drag
            val ot = ownTarget
            val order = linkOrder
            if (d != null) {
                val (dg, dw) = d
                if (ot != null) dropFromLinked(dg, dw, ot, copyMode)
                else if (order != null && order != latest.group(dg)?.let { g -> latest.inGroup(g, tab).map { it.id } }) {
                    groupUpdate(dg) { g -> g.copy(members = order + g.members.filter { it !in order }) }
                }
            }
            fromLinked = false; ownTarget = null; linkOrder = null; drag = null; origin = null
            return
        }
        val w = working
        val lt = linkTarget
        val dw = drag?.second
        if (lt != null && dw != null) {
            working = null
            dropLinked(lt, dw, copyMode)
        } else if (w != null && w != members(latest)) arrange(w) else working = null
        linkTarget = null
        opened.forEach { id -> groupUpdate(id) { it.copy(collapsed = false) } }
        opened.clear()
        drag = null
        origin = null
    }

    // Scroll while the dragged image is held near the top or bottom edge.
    val edge = with(LocalDensity.current) { 72.dp.toPx() }
    LaunchedEffect(drag != null) {
        while (drag != null) {
            val h = grid.layoutInfo.viewportSize.height
            val v = when {
                pointer.y < edge -> -(edge - pointer.y) / 4
                pointer.y > h - edge -> (pointer.y - (h - edge)) / 4
                else -> 0f
            }
            if (v != 0f) { grid.scrollBy(v); hoverAt(pointer) }
            withFrameNanos { }
        }
    }

    Scaffold(
        // Only while the top bar below the caption collapses with scrolling: with the header in the
        // caption bar no bar uses it, its collapse limit is never set and it would swallow every scroll.
        modifier = if (cap.integrated) Modifier else Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            AppTopBar(
                title = "Deskmat",
                leading = if (switcherInCaption) ({
                    if (multiScreen) SegmentSwitcher(
                        screens.map { Segment(screenLabel(it), screenIcon(it, foldable)) }, screens.indexOf(tab.screen),
                        { i -> onTab(Target.of(screens[i], tab.lock)) }, iconsOnly = stage == HeaderStage.ICON_SWITCHER,
                    )
                    SegmentSwitcher(
                        listOf(Segment("Wallpaper", Icons.Default.Home), Segment("Lock screen", Icons.Default.Lock)), if (tab.lock) 1 else 0,
                        { i -> onTab(Target.of(tab.screen, i == 1)) }, iconsOnly = true,
                    )
                    AutoToggle(tab in state.autoOn, onAuto)
                    BarIconButton(NextIcon, "Next image", onNext)
                }) else null,
                scrollBehavior = if (cap.integrated) null else scroll,
                actions = {
                    if (addInCaption) {
                        BarIconButton(NewGroupIcon, "New group", onNewGroup)
                        if (stage == HeaderStage.FULL) BarPillButton(Icons.Default.Add, "Add images") { onAdd(unassigned) }
                        else BarIconButton(Icons.Default.Add, "Add images") { onAdd(unassigned) }
                    }
                    BarIconButton(Icons.Default.Refresh, "Re-check triggers", onRefresh)
                    // The menu anchors to its parent, so the button and the menu share a Box.
                    Box {
                    BarIconButton(Icons.Default.MoreVert, "More") { menu = true }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Next image") }, leadingIcon = { Icon(NextIcon, null) }, onClick = { menu = false; onNext() })
                        DropdownMenuItem(text = { Text("New group") }, leadingIcon = { Icon(NewGroupIcon, null) }, onClick = { menu = false; onNewGroup() })
                        DropdownMenuItem(text = { Text("Import current ${tab.phrase}") }, leadingIcon = { Icon(ImportIcon, null) }, onClick = { menu = false; onImportCurrent() })
                        DropdownMenuItem(text = { Text("Flags") }, leadingIcon = { Icon(FlagIcon, null) }, onClick = { menu = false; onFlags() })
                        DropdownMenuItem(text = { Text("Settings, Termux & Routines") }, leadingIcon = { Icon(Icons.Default.Settings, null) }, onClick = { menu = false; onSettings() })
                    }
                    }
                },
            )
        },
        bottomBar = {
            if (!switcherInCaption && multiScreen) NavigationBar {
                screens.forEach { s ->
                    NavigationBarItem(
                        selected = s == tab.screen, onClick = { onTab(Target.of(s, tab.lock)) },
                        icon = { Icon(screenIcon(s, foldable), null) },
                        label = { Text(screenLabel(s)) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (!addInCaption) ExtendedFloatingActionButton(onClick = { onAdd(unassigned) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add images") })
        },
    ) { pad ->
        LazyVerticalGrid(
            state = grid,
            // Same column width and card height on every screen; images keep their shape inside.
            columns = GridCells.Adaptive(168.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + if (addInCaption) 16.dp else 88.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().pointerInput(tab) {
                // Initial pass, so the list doesn't scroll and the card doesn't click while an image is dragged.
                // Touch: hold, then drag. Mouse (DeX): drag straight away, like a desktop file manager.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val mouse = down.type == PointerType.Mouse
                    // Right-click (DeX): the card's menu (add to another group, remove from this one).
                    if (currentEvent.buttons.isSecondaryPressed) {
                        val hit = grid.itemAt(down.position)
                        (hit?.key as? String)?.let { key ->
                            when {
                                key.startsWith("wp:") -> key.removePrefix("wp:")
                                // Linked cards (another screen's group) have their own, shorter menu.
                                key.startsWith("lnk:") -> "lnk|" + key.removePrefix("lnk:")
                                else -> null
                            }
                        }?.let {
                            down.consume()
                            val info = hit!!
                            menuOffset = DpOffset(
                                (down.position.x - info.offset.x).toDp(),
                                (down.position.y - info.offset.y - info.size.height).toDp(),
                            )
                            cardMenuMode = null
                            cardMenu = it
                        }
                        return@awaitEachGesture
                    }
                    // Null when the press is held still for the long-press time; true when it ends or moves first.
                    val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var ended = false
                        while (!ended) {
                            val ch = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                            val moved = ch != null && (ch.position - down.position).getDistance() > viewConfiguration.touchSlop
                            if (mouse && moved && ch?.pressed == true) return@withTimeoutOrNull null
                            ended = ch == null || !ch.pressed || ch.isConsumed || moved
                        }
                        true
                    }
                    if (early != null) return@awaitEachGesture
                    val hit = grid.itemAt(down.position) ?: return@awaitEachGesture
                    val key = hit.key as? String ?: return@awaitEachGesture
                    if (!key.startsWith("wp:") && !key.startsWith("lnk:")) return@awaitEachGesture
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    fromLinked = key.startsWith("lnk:")
                    val (g0, w0) = key.substringAfter(':').split("|")
                    if (fromLinked) {
                        linkOrder = latest.group(g0)?.let { g -> latest.inGroup(g, tab).map { it.id } }
                        ownTarget = null
                    } else working = members(latest)
                    grab = down.position - Offset(hit.offset.x.toFloat(), hit.offset.y.toFloat())
                    pointer = down.position
                    drag = g0 to w0
                    origin = g0
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        copyMode = ev.keyboardModifiers.isCtrlPressed
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        ch.consume()
                        if (!ch.pressed) break
                        pointer = ch.position
                        hoverAt(pointer)
                    }
                    drop()
                }
            },
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "status") {
                StatusCard(state.byId(showingId)?.on(tab), status?.reason, ratio)
            }
            state.pinLabel(tab)?.let { pinned ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "pinned") { PinnedBanner(tab.phrase, pinned, onResume) }
            }
            if (!switcherInCaption) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "slot") {
                    SlotCard(state, tab, onTab, onAuto, onNext)
                }
            }
            // Other screens' groups linked here: first (they take priority), shown with this screen's crops.
            // Their images belong to the other screen, so they aren't dragged here (keys outside "wp:").
            for (g in linkedIn) {
                val base = state.inGroup(g, tab).map { it.on(tab) }
                val lo = linkOrder
                val inGroup = if (fromLinked && drag?.first == g.id && lo != null) lo.mapNotNull { id -> base.firstOrNull { it.id == id } } else base
                val open = !g.collapsed
                item(span = { GridItemSpan(maxLineSpan) }, key = "lgroup:${g.id}") {
                    GroupHeader(
                        g, state, inGroup.size, active = g.id == activeId, open = open,
                        onToggle = { onGroup(g.id) { it.copy(collapsed = open) } },
                        onShuffle = { on -> onGroup(g.id) { it.copy(shuffle = on) } },
                        onAdd = { onAdd(g) }, onEdit = { onEditGroup(g) },
                        linkedFrom = g.target,
                        // Outlined while an image is held over it: dropping moves the image into it.
                        modifier = Modifier.animateItem().then(
                            if (drag != null && linkTarget == g.id) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)) else Modifier
                        ),
                    )
                }
                if (!open) continue
                items(inGroup, key = { "lnk:${g.id}|${it.id}" }) { wp ->
                    val skipped = wp.id in g.skipped
                    val dragged = fromLinked && drag == (g.id to wp.id)
                    val mod = if (dragged) Modifier.zIndex(1f).graphicsLayer {
                        grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "lnk:${g.id}|${wp.id}" }?.let { info ->
                            translationX = pointer.x - grab.x - info.offset.x
                            translationY = pointer.y - grab.y - info.offset.y
                        }
                        scaleX = 1.05f; scaleY = 1.05f
                        shadowElevation = 12.dp.toPx(); shape = RoundedCornerShape(12.dp); clip = true
                    } else Modifier
                    Box(if (dragged) Modifier.zIndex(1f) else Modifier.animateItem()) {
                    WallpaperCard(
                        wp, ratio, wp.id == showingId, state.pins[tab] == wp.id,
                        position = if (g.shuffle || skipped) null else inGroup.filter { it.id !in g.skipped }.indexOf(wp) + 1,
                        groupCount = 0, skipped = skipped,
                        onSkip = { onSkip(g.id, wp.id, !skipped) },
                        modifier = mod,
                        lowRes = lowRes(wp),
                    ) { onOpenLinked(wp, inGroup.map { it.id }, g) }
                    val entry = "lnk|${g.id}|${wp.id}"
                    DropdownMenu(expanded = cardMenu == entry, offset = menuOffset, onDismissRequest = { cardMenu = null; cardMenuMode = null }) {
                      // The image belongs to the group's own screen; copies and moves start from there.
                      val real = state.byId(wp.id) ?: wp
                      val mode = cardMenuMode
                      if (mode != null) {
                        otherSlots.filter { it != real.target }.forEach { t ->
                            DropdownMenuItem(
                                text = { Text((if (mode == "copy") "Copy to " else "Move to ") + t.phrase) },
                                leadingIcon = { Icon(if (t.lock) Icons.Default.Lock else screenIcon(t.screen, foldable), null) },
                                onClick = { cardMenu = null; cardMenuMode = null; if (mode == "copy") onCopyTo(real, t) else onMoveTo(real, t) },
                            )
                        }
                      } else {
                        DropdownMenuItem(text = { Text("Open") }, leadingIcon = { Icon(OpenIcon, null) }, onClick = { cardMenu = null; onOpenLinked(wp, inGroup.map { it.id }, g) })
                        DropdownMenuItem(text = { Text("Copy to…") }, leadingIcon = { Icon(CopyIcon, null) }, onClick = { cardMenuMode = "copy" })
                        DropdownMenuItem(text = { Text("Move to…") }, leadingIcon = { Icon(MoveIcon, null) }, onClick = { cardMenuMode = "move" })
                        DropdownMenuItem(
                            text = { Text(if (skipped) "Use in Automatic" else "Skip in Automatic") },
                            leadingIcon = { Icon(if (skipped) Icons.Default.CheckCircle else SkipIcon, null) },
                            onClick = { cardMenu = null; onSkip(g.id, wp.id, !skipped) },
                        )
                        DropdownMenuItem(text = { Text("Remove from ${g.name}") }, leadingIcon = { Icon(RemoveIcon, null) }, onClick = { cardMenu = null; onRemoveFromGroup(g.id, wp.id) })
                        DropdownMenuItem(text = { Text("Edit ${g.name}") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { cardMenu = null; onEditGroup(g) })
                      }
                    }
                    }
                }
            }
            for (g in groups) {
                val inGroup = lists[g.id].orEmpty().mapNotNull { state.byId(it) }
                val open = !g.collapsed || g.id in opened
                item(span = { GridItemSpan(maxLineSpan) }, key = "group:${g.id}") {
                    GroupHeader(
                        g, state, inGroup.size, active = g.id == activeId, open = open,
                        onToggle = { onGroup(g.id) { it.copy(collapsed = open) } },
                        onShuffle = { on -> onGroup(g.id) { it.copy(shuffle = on) } },
                        onAdd = { onAdd(g) }, onEdit = { onEditGroup(g) },
                        modifier = Modifier.animateItem().then(
                            if (fromLinked && drag != null && ownTarget == g.id) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)) else Modifier
                        ),
                    )
                }
                if (!open) continue
                if (inGroup.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "empty:${g.id}") {
                        EmptyGroup(g, tab, onAdd = { onAdd(g) }, onImportCurrent = onImportCurrent, modifier = Modifier.animateItem())
                    }
                }
                items(inGroup, key = { "wp:${g.id}|${it.id}" }) { wp ->
                    val entry = "${g.id}|${wp.id}"
                    val dragged = drag == (g.id to wp.id)
                    val mod = if (dragged) Modifier.zIndex(1f).graphicsLayer {
                        grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "wp:$entry" }?.let { info ->
                            translationX = pointer.x - grab.x - info.offset.x
                            translationY = pointer.y - grab.y - info.offset.y
                        }
                        scaleX = 1.05f; scaleY = 1.05f
                        shadowElevation = 12.dp.toPx(); shape = RoundedCornerShape(12.dp); clip = true
                    } else Modifier.animateItem()
                    val skipped = wp.id in g.skipped
                    Box {
                        WallpaperCard(
                            wp, ratio, wp.id == showingId, state.pins[tab] == wp.id,
                            // Order numbers count only the images Automatic uses.
                            position = if (g.shuffle || skipped) null else inGroup.filter { it.id !in g.skipped }.indexOf(wp) + 1,
                            groupCount = state.groupsOf(wp).size,
                            skipped = skipped,
                            onSkip = { onSkip(g.id, wp.id, !skipped) },
                            modifier = mod,
                            lowRes = lowRes(wp),
                        ) { onOpen(wp, inGroup.map { it.id }, g) }
                        DropdownMenu(expanded = cardMenu == entry, offset = menuOffset, onDismissRequest = { cardMenu = null; cardMenuMode = null }) {
                          val mode = cardMenuMode
                          if (mode != null) {
                            otherSlots.forEach { t ->
                                DropdownMenuItem(
                                    text = { Text((if (mode == "copy") "Copy to " else "Move to ") + t.phrase) },
                                leadingIcon = { Icon(if (t.lock) Icons.Default.Lock else screenIcon(t.screen, foldable), null) },
                                    onClick = { cardMenu = null; cardMenuMode = null; if (mode == "copy") onCopyTo(wp, t) else onMoveTo(wp, t) },
                                )
                            }
                          } else {
                            DropdownMenuItem(text = { Text("Open") }, leadingIcon = { Icon(OpenIcon, null) }, onClick = { cardMenu = null; onOpen(wp, inGroup.map { it.id }, g) })
                            if (otherSlots.isNotEmpty()) {
                                DropdownMenuItem(text = { Text("Copy to…") }, leadingIcon = { Icon(CopyIcon, null) }, onClick = { cardMenuMode = "copy" })
                                DropdownMenuItem(text = { Text("Move to…") }, leadingIcon = { Icon(MoveIcon, null) }, onClick = { cardMenuMode = "move" })
                            }
                            DropdownMenuItem(
                                text = { Text(if (skipped) "Use in Automatic" else "Skip in Automatic") },
                            leadingIcon = { Icon(if (skipped) Icons.Default.CheckCircle else SkipIcon, null) },
                                onClick = { cardMenu = null; onSkip(g.id, wp.id, !skipped) },
                            )
                            state.groupsFor(tab).filter { wp.id !in it.members }.forEach { other ->
                                DropdownMenuItem(text = { Text("Also add to ${other.name}") }, leadingIcon = { Icon(AddToIcon, null) }, onClick = { cardMenu = null; onAddToGroup(other.id, wp.id) })
                            }
                            if (!g.isUnassigned) {
                                DropdownMenuItem(text = { Text("Remove from ${g.name}") }, leadingIcon = { Icon(RemoveIcon, null) }, onClick = { cardMenu = null; onRemoveFromGroup(g.id, wp.id) })
                            }
                          }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "newGroup") {
                Column {
                    OutlinedButton(onClick = onNewGroup) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("New group") }
                    Text(
                        "The first group whose triggers match is active; with none matching, Unassigned is. " +
                            "Hold and drag an image to reorder it or move it to another group; hold Ctrl while dragging to add it to that group as well. " +
                            "Right-click an image for more.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

private fun LazyGridState.itemAt(pos: Offset) = layoutInfo.visibleItemsInfo.firstOrNull { r ->
    pos.x >= r.offset.x && pos.x < r.offset.x + r.size.width && pos.y >= r.offset.y && pos.y < r.offset.y + r.size.height
}

@Composable
private fun StatusCard(showing: Wallpaper?, reason: String?, ratio: Float) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showing != null) {
                val m = Modifier.height(64.dp).aspectRatio(ratio)
                WallpaperImage(showing.file, showing.crop, 256, m.clipMedium())
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("Now showing", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(showing?.name ?: "Nothing yet", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(reason ?: "Not run yet", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Wallpaper / lock screen switch and the Automatic toggle, when they don't fit in the caption bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlotCard(state: LibraryState, tab: Target, onTab: (Target) -> Unit, onAuto: (Boolean) -> Unit, onNext: () -> Unit) {
    val on = tab in state.autoOn
    Card(
        colors = CardDefaults.cardColors(containerColor = if (on) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.padding(end = 8.dp).fillMaxWidth()) {
                listOf(false, true).forEachIndexed { i, lock ->
                    SegmentedButton(
                        selected = tab.lock == lock, onClick = { onTab(Target.of(tab.screen, lock)) },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                        icon = { Icon(if (lock) Icons.Default.Lock else Icons.Default.Home, null, Modifier.size(18.dp)) },
                    ) { Text(if (lock) "Lock screen" else "Wallpaper") }
                }
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Automatic", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (on) autoSummary(state.rulesFor(state.activeGroupOf(tab)), tab.isDex) + " (active group's rules)"
                        else "Paused: stays on the current image until you tap Next",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                AutoToggle(on, onAuto)
                IconButton(onClick = onNext) { Icon(NextIcon, "Next image") }
            }
        }
    }
}

@Composable
private fun GroupHeader(
    g: Group,
    state: LibraryState,
    count: Int,
    active: Boolean,
    open: Boolean,
    onToggle: () -> Unit,
    onShuffle: (Boolean) -> Unit,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    /** Shown on a linked screen: the screen the group belongs to. */
    linkedFrom: Target? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Row(Modifier.clickable(onClick = onToggle).padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (open) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight, if (open) "Collapse" else "Expand")
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(g.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (active) { Spacer(Modifier.width(8.dp)); Badge("Active") }
                }
                val trig = when {
                    g.isUnassigned -> "When no trigger matches"
                    g.triggers.isEmpty() -> "No triggers yet"
                    else -> g.triggers.joinToString(" OR ") { Rules.describe(it) }
                }
                val details = listOfNotNull(
                    linkedFrom?.let { "linked from the ${it.phrase}" }
                        ?: g.links.takeIf { it.isNotEmpty() }?.let { l -> "linked to " + l.joinToString { it.phrase } },
                    "$count image${if (count == 1) "" else "s"}",
                    if (g.global) "on every screen" else null,
                    if (g.shuffle) "shuffle" else "in order",
                    if (g.auto != null) "own automatic rules" else null,
                )
                Text(
                    "$trig · ${details.joinToString(" · ")}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            ShuffleSwitch(g.shuffle, onShuffle)
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onAdd) { Icon(Icons.Default.Add, "Add images to ${g.name}") }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit ${g.name}") }
        }
    }
}

@Composable
private fun EmptyGroup(g: Group, tab: Target, onAdd: () -> Unit, onImportCurrent: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (g.isUnassigned) "No ${tab.phrase} images here" else "Empty group",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "Add images, or hold and drag one here.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onAdd) { Text("Add images") }
                if (g.isUnassigned) TextButton(onClick = onImportCurrent) { Text("Import current") }
            }
        }
    }
}

/** Height of every card's image frame, whatever the screen's shape. */
private val CARD_IMAGE_HEIGHT = 128.dp

@Composable
private fun WallpaperCard(
    wp: Wallpaper,
    ratio: Float,
    showing: Boolean,
    pinned: Boolean,
    position: Int?,
    /** User groups the image is in; shown when it is in more than one. */
    groupCount: Int,
    /** Automatic and Next skip it in this group: the card is dimmed. */
    skipped: Boolean,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    /** The crop is stretched this much (or more) to fill the screen: shown as "Low res". */
    lowRes: Boolean = false,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier.alpha(if (skipped) 0.55f else 1f),
        border = if (pinned) BorderStroke(2.dp, MaterialTheme.colorScheme.error) else if (showing) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Box {
            // Fixed-height frame; the image keeps the screen's shape, padded on the sides or top and bottom.
            Box(Modifier.fillMaxWidth().height(CARD_IMAGE_HEIGHT).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                WallpaperImage(
                    wp.file, wp.crop, 512,
                    Modifier.align(Alignment.Center).fillMaxHeight().aspectRatio(ratio, matchHeightConstraintsFirst = true),
                )
            }
            Row(Modifier.align(Alignment.TopStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (pinned) Badge("Pinned", PushPinIcon) else if (showing) Badge("Showing")
                if (lowRes) Badge("Low res", warn = true)
            }
            if (position != null) {
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f), modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    Text("$position", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
            // In/out of Automatic for this group: filled = used, outlined pause = skipped.
            Surface(
                onClick = onSkip,
                shape = CircleShape,
                color = if (skipped) MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f) else MaterialTheme.colorScheme.primary,
                contentColor = if (skipped) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(32.dp)
                    .semantics { contentDescription = if (skipped) "Skipped in Automatic, tap to use" else "Used in Automatic, tap to skip" },
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(if (skipped) PauseIcon else AutoIcon, null, Modifier.size(18.dp)) }
            }
        }
        Column(Modifier.padding(12.dp)) {
            Text(wp.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(if (skipped) "Skipped in Automatic" else null, wp.command?.let { "⌘ $it" }, if (groupCount > 1) "in $groupCount groups" else null)
                    .joinToString(" · ").ifEmpty { "No command" },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = if (wp.command == null && groupCount < 2 && !skipped) Modifier.alpha(0.6f) else Modifier,
            )
        }
    }
}

@Composable
fun Badge(text: String, icon: ImageVector? = null, warn: Boolean = false) {
    val pin = icon != null || warn
    Surface(shape = MaterialTheme.shapes.small, color = if (pin) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            val fg = if (pin) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
            if (icon != null) {
                Icon(icon, null, Modifier.size(14.dp), tint = fg)
                Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.labelSmall, color = fg)
        }
    }
}

fun intervalLabel(min: Int) = when {
    min < 60 -> "$min min"
    min % 60 == 0 -> "${min / 60} h"
    else -> "${min / 60} h ${min % 60} min"
}
