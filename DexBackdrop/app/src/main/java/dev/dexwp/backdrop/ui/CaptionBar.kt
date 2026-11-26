package dev.dexwp.backdrop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Header layout for desktop (DeX) windows. When the window has a caption bar and is wide
 * enough, the caption bar is made transparent (touches pass through to the app) and the
 * header is drawn inside it, clear of the caption buttons. Whenever the free space left in
 * the row drops under [CaptionInfo.FREE_MIN], the next stage applies ([HeaderStage]):
 * full → screen switcher shows icons only → switchers (and the Automatic toggle) move to the body →
 * Add moves to a floating button → caption merge is turned off (header below an opaque caption).
 */
data class CaptionInfo(
    /** Header is drawn inside the transparent caption bar. */
    val integrated: Boolean = false,
    /**
     * Ask for a transparent caption bar. DeX force-consumes the caption inset (the app sees 0)
     * until this is requested, so it has to be requested before the caption can be measured.
     */
    val wantTransparent: Boolean = true,
    val rowHeight: Dp = 0.dp,
    /** Size of each control in the row (icon buttons, switcher, Add). */
    val control: Dp = 48.dp,
    /** Space kept clear for the caption buttons on each side. */
    val startPad: Dp = 0.dp,
    val endPad: Dp = 0.dp,
    /** Width left for our controls. */
    val available: Dp = 0.dp,
) {
    /** Width of the always-present right-hand actions (refresh/back + menu/delete). */
    val actionsWidth get() = control * 2 + GAP * 2

    companion object {
        val MIN_CONTROL = 32.dp
        val MAX_CONTROL = 44.dp
        val V_PAD = 4.dp
        val GAP = 6.dp
        /** Keep at least this much empty space in the caption row; less triggers the next stage. */
        val FREE_MIN = 150.dp
    }
}

val LocalCaption = staticCompositionLocalOf { CaptionInfo() }
private val LocalControl = compositionLocalOf { 48.dp }

/** Caption height and button reservations last seen while the caption was visible to the app. */
private data class CaptionGeometry(val heightPx: Int, val leftPx: Int, val rightPx: Int)

@Composable
fun rememberCaption(): CaptionInfo {
    val density = LocalDensity.current
    val captionPx = WindowInsets.captionBar.getTop(density)
    val view = LocalView.current
    var known by remember { mutableStateOf<CaptionGeometry?>(null) }
    // Desktop caption bars that apps can draw in exist from Android 15; older phones never get here.
    if (captionPx > 0 && android.os.Build.VERSION.SDK_INT >= 35) {
        var left = 0
        var right = 0
        // Rects touch the window edges: a left one starts at 0, a right one ends at the window width.
        view.rootWindowInsets?.getBoundingRects(android.view.WindowInsets.Type.captionBar())?.forEach { r ->
            if (r.left == 0) left = maxOf(left, r.right) else right = maxOf(right, r.width())
        }
        // DeX reports 1.4h of empty space before minimise (h = caption height). Our buttons stay out
        // of it: on One UI 8 a press there starts a window drag even inside a gesture exclusion rect.
        val g = CaptionGeometry(captionPx, left, right)
        if (g != known) SideEffect { known = g }
    }
    val g = known ?: return CaptionInfo(wantTransparent = true)
    return with(density) {
        val caption = g.heightPx.toDp()
        val control = (caption - CaptionInfo.V_PAD * 2).coerceIn(CaptionInfo.MIN_CONTROL, CaptionInfo.MAX_CONTROL)
        val start = g.leftPx.toDp()
        val end = g.rightPx.toDp()
        // Width-based, so turning the transparent caption off (which hides its inset) can't flip the decision back.
        val available = LocalConfiguration.current.screenWidthDp.dp - start - end - 16.dp
        val wide = available - (control * 2 + CaptionInfo.GAP * 2) >= CaptionInfo.FREE_MIN
        CaptionInfo(
            integrated = wide && captionPx > 0,
            wantTransparent = wide,
            rowHeight = maxOf(caption, control + CaptionInfo.V_PAD * 2),
            control = control,
            startPad = start,
            endPad = end,
            available = available,
        )
    }
}

/**
 * Top bar for every screen: inside the caption bar when integrated, else a Material top app bar.
 * [leading] sits on the left (e.g. the tab switcher); [actions] on the right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    navigationIcon: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val cap = LocalCaption.current
    if (!cap.integrated) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { navigationIcon?.invoke() },
            actions = actions,
            scrollBehavior = scrollBehavior,
            windowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        )
        return
    }
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        CompositionLocalProvider(LocalControl provides cap.control) {
            Row(
                Modifier.fillMaxWidth().height(cap.rowHeight)
                    .padding(start = cap.startPad + 8.dp, end = cap.endPad + 8.dp, top = CaptionInfo.V_PAD, bottom = CaptionInfo.V_PAD),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CaptionInfo.GAP),
            ) {
                navigationIcon?.invoke()
                leading?.invoke()
                Text(
                    title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = if (cap.control < 40.dp) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
                actions()
            }
        }
    }
}

/** Icon button that matches the header's control size. */
@Composable
fun BarIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val size = LocalControl.current
    IconButton(onClick = onClick, modifier = Modifier.size(size).systemGestureExclusion()) {
        Icon(icon, description, Modifier.size(size * 0.55f))
    }
}

/** How much of the gallery header stays in the caption bar. */
enum class HeaderStage { FULL, ICON_SWITCHER, NO_SWITCHER, NO_ADD }

/**
 * Picks the first stage that leaves at least [CaptionInfo.FREE_MIN] empty in the caption row.
 * The header holds the screen switcher ([screenLabels]), the wallpaper/lock switcher (icons),
 * the Automatic toggle and Add.
 */
@Composable
fun rememberHeaderStage(cap: CaptionInfo, addLabel: String, screenLabels: List<String>): HeaderStage {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    fun textW(t: String): Dp = with(density) { measurer.measure(t, style).size.width.toDp() }
    val gap = CaptionInfo.GAP
    val c = cap.control
    // Add images and New group travel together.
    val add = textW(addLabel) + PILL_ICON + PILL_ICON_GAP + PILL_PAD * 2 + gap + c + gap
    val addIcons = (c + gap) * 2
    // Automatic switch and Next.
    val toggle = SWITCH_W + gap + c + gap
    val kinds = (c + SEG_ICON_EXTRA) * 2 + gap
    val screensText = if (screenLabels.isEmpty()) 0.dp else (screenLabels.maxOf { textW(it) } + SEG_PAD * 2) * screenLabels.size + gap
    val screensIcons = if (screenLabels.isEmpty()) 0.dp else (c + SEG_ICON_EXTRA) * screenLabels.size + gap
    fun free(items: List<Dp>) = cap.available - cap.actionsWidth - items.fold(0.dp) { a, b -> a + b }
    return when {
        free(listOf(toggle, kinds, screensText, add)) >= CaptionInfo.FREE_MIN -> HeaderStage.FULL
        // Shrink stage: the screen switcher and "Add images" both turn into icons.
        free(listOf(toggle, kinds, screensIcons, addIcons)) >= CaptionInfo.FREE_MIN -> HeaderStage.ICON_SWITCHER
        free(listOf(add)) >= CaptionInfo.FREE_MIN -> HeaderStage.NO_SWITCHER
        else -> HeaderStage.NO_ADD
    }
}

/** Width of a Material 3 switch. */
private val SWITCH_W = 52.dp
private val SEG_PAD = 14.dp
private val SEG_ICON_EXTRA = 12.dp
private val PILL_PAD = 14.dp
private val PILL_ICON = 18.dp
private val PILL_ICON_GAP = 6.dp

/** One option of a [SegmentSwitcher]. */
data class Segment(val label: String, val icon: ImageVector)

/** Segmented switcher sized to the header controls; equal segments, content centred. */
@Composable
fun SegmentSwitcher(segments: List<Segment>, selected: Int, onSelect: (Int) -> Unit, iconsOnly: Boolean) {
    val size = LocalControl.current
    val scheme = MaterialTheme.colorScheme
    // Equal segments: as wide as the longest label.
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    val segW = with(density) { segments.maxOf { measurer.measure(it.label, style).size.width }.toDp() } + SEG_PAD * 2
    Row(
        Modifier.height(size).systemGestureExclusion().clip(CircleShape).border(1.dp, scheme.outline, CircleShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEachIndexed { i, seg ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(scheme.outline))
            val isSelected = i == selected
            Box(
                Modifier.fillMaxHeight()
                    .background(if (isSelected) scheme.secondaryContainer else Color.Transparent)
                    .clickable { onSelect(i) }
                    .width(if (iconsOnly) size + SEG_ICON_EXTRA else segW),
                contentAlignment = Alignment.Center,
            ) {
                val fg = if (isSelected) scheme.onSecondaryContainer else scheme.onSurface
                if (iconsOnly) {
                    Icon(seg.icon, seg.label, Modifier.size(size * 0.5f), tint = fg)
                } else {
                    Text(seg.label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** Automatic on/off switch: play in the thumb while Automatic runs, pause while it is paused. */
@Composable
fun AutoToggle(on: Boolean, onToggle: (Boolean) -> Unit) {
    Switch(
        checked = on,
        onCheckedChange = onToggle,
        thumbContent = { Icon(if (on) Icons.Default.PlayArrow else PauseIcon, null, Modifier.size(SwitchDefaults.IconSize)) },
        modifier = Modifier.systemGestureExclusion()
            .semantics { contentDescription = if (on) "Automatic on" else "Automatic paused" },
    )
}

/** Shuffle on/off switch with a die in the thumb. */
@Composable
fun ShuffleSwitch(on: Boolean, onToggle: (Boolean) -> Unit) {
    Switch(
        checked = on,
        onCheckedChange = onToggle,
        thumbContent = { Icon(DiceIcon, null, Modifier.size(SwitchDefaults.IconSize)) },
        modifier = Modifier.semantics { contentDescription = if (on) "Shuffle on" else "Shuffle off" },
    )
}

/** Pill button (icon + label) at the header control height, content centred. */
@Composable
fun BarPillButton(icon: ImageVector, text: String, danger: Boolean = false, onClick: () -> Unit) {
    val size = LocalControl.current
    Surface(
        onClick = onClick,
        shape = CircleShape,
        // Destructive actions (Delete) are red so they read as such.
        color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (danger) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.height(size).systemGestureExclusion(),
    ) {
        Row(Modifier.padding(horizontal = PILL_PAD), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(icon, null, Modifier.size(PILL_ICON))
            Spacer(Modifier.width(PILL_ICON_GAP))
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}
