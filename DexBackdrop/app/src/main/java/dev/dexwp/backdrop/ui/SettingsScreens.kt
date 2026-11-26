package dev.dexwp.backdrop.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.dexwp.backdrop.data.LibraryState
import androidx.compose.material3.Slider
import dev.dexwp.backdrop.data.WidgetStyle
import dev.dexwp.backdrop.widget.Widgets
import androidx.compose.material3.AssistChip
import dev.dexwp.backdrop.model.AutoRules
import dev.dexwp.backdrop.model.Screen
import dev.dexwp.backdrop.model.Target
import android.content.Intent
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import dev.dexwp.backdrop.engine.Screens
import dev.dexwp.backdrop.engine.Seen
import kotlin.math.roundToInt
import dev.dexwp.backdrop.model.Condition
import dev.dexwp.backdrop.remote.RemoteAccess
import dev.dexwp.backdrop.remote.Scope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlagsScreen(
    state: LibraryState,
    onBack: () -> Unit,
    onSet: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onShortcut: (String, Boolean) -> Unit,
) {
    val referenced = state.groups.flatMap { it.triggers }.flatMap { it.conditions }.filterIsInstance<Condition.Flag>().map { it.name }
    val names = (state.flags.keys + referenced).toSortedSet()
    var newName by remember { mutableStateOf("") }
    Scaffold(topBar = {
        AppTopBar(title = "Flags", navigationIcon = { BarIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) })
    }) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            item {
                Text(
                    "Flags are on/off switches for triggers. Routines, Termux (dexwp flag work on) and broadcasts set them; toggle here to test. " +
                        "Mark a flag as Shortcut to get “<flag> on” and “<flag> off” app shortcuts, which Modes and Routines can run " +
                        "(Then → Apps → Open an app or do an app action → Deskmat).",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(names.toList()) { name ->
                ListItem(
                    headlineContent = { Text(name) },
                    supportingContent = { if (name in referenced) Text("Used by triggers") },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = name in state.shortcutFlags,
                                onClick = { onShortcut(name, name !in state.shortcutFlags) },
                                label = { Text("Shortcut") },
                            )
                            Spacer(Modifier.width(8.dp))
                            Switch(checked = state.flags[name] ?: false, onCheckedChange = { onSet(name, it) })
                            IconButton(onClick = { onDelete(name) }) { Icon(Icons.Default.Delete, "Forget") }
                        }
                    },
                )
            }
            item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newName, { newName = it.replace(Regex("\\s"), "-") }, label = { Text("New flag") }, singleLine = true, modifier = Modifier.weight(1f))
                    IconButton(enabled = newName.isNotBlank(), onClick = { onSet(newName.trim(), true); newName = "" }) { Icon(Icons.Default.Add, "Add") }
                }
            }
        }
    }
}

data class PermissionInfo(val location: Boolean, val background: Boolean, val allFiles: Boolean, val usage: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: LibraryState,
    perms: PermissionInfo,
    onBack: () -> Unit,
    onAspect: (String) -> Unit,
    onLocation: () -> Unit,
    onBackground: () -> Unit,
    onAllFiles: () -> Unit,
    onSaveScript: () -> Unit,
    onShuffleSettings: ((LibraryState) -> LibraryState) -> Unit,
    onThemeFromImage: (Boolean) -> Unit,
    onDownsample: (String) -> Unit,
    /** False on phones without Samsung DeX: DeX-only settings are hidden. */
    dex: Boolean,
    onWidgetPadding: (WidgetStyle, Int) -> Unit,
    onUsage: () -> Unit,
    /** Screens available on this phone (for the rotation switches). */
    screens: List<Screen>,
    onRotating: (Target, Boolean) -> Unit,
    onAddWidget: (Widgets.Kind, WidgetStyle) -> Unit,
) {
    Scaffold(topBar = {
        AppTopBar(title = "Settings", navigationIcon = { BarIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) })
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            if (dex) {
            SectionTitle("DeX crop shape")
            Text("Used for new DeX images and DeX images without a saved crop. Auto follows the connected DeX monitor. Phone screens use their own shape.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("auto", "16:10", "16:9", "21:9", "32:9").forEach { a ->
                    FilterChip(selected = state.aspect == a, onClick = { onAspect(a) }, label = { Text(if (a == "auto") "Auto" else a) })
                }
            }
            }

            SectionTitle("Appearance")
            ListItem(
                headlineContent = { Text("Colours from the current image") },
                supportingContent = { Text("App colours follow the image on screen; off uses your system colour palette") },
                trailingContent = { Switch(checked = state.themeFromImage, onCheckedChange = onThemeFromImage) },
            )

            SectionTitle("Image quality")
            DownsampleRow(state, onDownsample, dex)
            SectionTitle("Screens that rotate")
            Text(
                "A screen that turns sideways keeps enough of each image for both orientations, and its images get a landscape crop. " +
                    "Others are cut to exactly the screen. DeX never rotates.",
                style = MaterialTheme.typography.bodySmall,
            )
            Target.entries.filter { !it.isDex && it.screen in screens }.forEach { t ->
                ListItem(
                    headlineContent = { Text(t.label) },
                    trailingContent = { Switch(checked = t in state.rotating, onCheckedChange = { on -> onRotating(t, on) }) },
                )
            }

            SectionTitle("Automatic: default rules")
            Text(
                "With Automatic on for a screen, its active group moves to the next image (in order, or at random with the group's Shuffle) " +
                    "when these rules say so. Groups can set their own rules instead. Triggers still switch between groups.",
                style = MaterialTheme.typography.bodySmall,
            )
            AutoRulesEditor(state.autoDefaults, dex = if (dex) null else false, showDex = dex) { r -> onShuffleSettings { it.copy(autoDefaults = r) } }
            if (dex) Text(
                "DeX is ${if (state.dexActive) "connected" else "not connected"}.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp),
            )
            Text(
                "For other moments (screen off, unlock, charging, a place), use Modes and Routines: " +
                    "its “Set Deskmat wallpaper” action has a “Next image” choice per screen.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )

            SectionTitle("Widgets")
            Text(
                "Next image (1×1) and Slide to change (3×1 or 1×3), each in a Material and a One UI style. " +
                    "Padding is the space between the widget's edge and its content.",
                style = MaterialTheme.typography.bodySmall,
            )
            WidgetStyle.entries.forEach { st ->
                val pad = state.widgetPadding[st] ?: st.defaultPadding
                ListItem(
                    headlineContent = { Text("${st.label} padding: $pad dp") },
                    supportingContent = {
                        Column {
                            Slider(value = pad.toFloat(), onValueChange = { onWidgetPadding(st, it.roundToInt()) }, valueRange = 0f..24f, steps = 23)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AssistChip(onClick = { onAddWidget(Widgets.Kind.NEXT, st) }, label = { Text("Add Next image") })
                                AssistChip(onClick = { onAddWidget(Widgets.Kind.SLIDE, st) }, label = { Text("Add Slide to change") })
                            }
                        }
                    },
                )
            }

            SectionTitle("Permissions")
            PermissionRow("Location", "Sunrise/sunset and places", perms.location, onLocation)
            PermissionRow("Location all the time", "Places switch while the app is closed", perms.background, onBackground)
            PermissionRow("All files access", "Import the current wallpaper; dexwp file <path>", perms.allFiles, onAllFiles)
            PermissionRow("Usage access", "“Must have been seen”: knows when you've been to the home screen", perms.usage, onUsage)
            ListItem(
                headlineContent = { Text("Geofencing") },
                supportingContent = { Text(state.geofenceStatus + ". Places switch on enter/exit events from Google Play services; no polling.") },
            )

            ConnectedApps()

            SectionTitle("Termux")
            Text("Save the dexwp script to Downloads, then in Termux (after termux-setup-storage):", style = MaterialTheme.typography.bodyMedium)
            Code("cp ~/storage/downloads/dexwp \$PREFIX/bin/ && chmod +x \$PREFIX/bin/dexwp")
            FilledTonalButton(onClick = onSaveScript, modifier = Modifier.padding(top = 8.dp)) { Text("Save dexwp to Downloads") }
            Code(
                "dexwp set work            # pin by command name\n" +
                    "dexwp set beach cover     # slots: home lock main mainlock cover coverlock\n" +
                    "dexwp clear               # back to triggers\n" +
                    "dexwp next                # advance the active group\n" +
                    "dexwp flag focus on|off|toggle\n" +
                    "dexwp file ~/storage/shared/Pictures/x.jpg\n" +
                    "dexwp status | list | flags",
            )

            SectionTitle("Broadcasts (Tasker, adb, any automation app)")
            Code(
                "am broadcast -n dev.dexwp.backdrop/.system.CommandReceiver \\\n" +
                    "  -a dev.dexwp.backdrop.COMMAND --es cmd set --es arg work\n" +
                    "# extras: cmd = set|clear|next|automatic|flag|unflag|file|refresh|status|list|flags\n" +
                    "#         arg, target = home|lock|main|mainlock|cover|coverlock, state = on|off|toggle",
            )

            SectionTitle("Modes and Routines")
            Text(
                "Then → add action → Display → Set Deskmat wallpaper: pick an image, a group, Automatic or Next image for a screen. " +
                    "Or Apps → Open an app or do an app action → Deskmat: images and groups with a command name appear as shortcuts, plus “DeX: automatic”. " +
                    "Use flags in triggers for AND/OR logic.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** "On DeX start/end, every 1 h" etc. [dex] false: the interval also runs without DeX. */
fun autoSummary(r: AutoRules, dex: Boolean): String {
    val parts = listOfNotNull(
        when {
            r.onDexStart && r.onDexEnd -> "each DeX start and end"
            r.onDexStart -> "each DeX start"
            r.onDexEnd -> "each DeX end"
            else -> null
        },
        if (r.onScreenOff) "each screen-off" else null,
        when {
            r.onFold && r.onUnfold -> "each fold and unfold"
            r.onFold -> "each fold"
            r.onUnfold -> "each unfold"
            else -> null
        },
        if (r.interval) "every ${intervalLabel(r.intervalMin)}" + if (dex) " while DeX runs" else "" else null,
    )
    return if (parts.isEmpty()) "Only when you tap Next" else "Next image on " + parts.joinToString(" and ")
}

/**
 * Switches for [AutoRules]. [dex]: the slot is a DeX one (null = shown for all slots).
 * [showDex] false hides the DeX start/end switches (phones without DeX).
 */
@Composable
fun AutoRulesEditor(r: AutoRules, dex: Boolean?, showDex: Boolean = true, onChange: (AutoRules) -> Unit) {
    if (showDex) {
        var open by remember { mutableStateOf(false) }
        val current = when {
            r.onDexStart && r.onDexEnd -> "On startup and exit"
            r.onDexStart -> "On startup"
            r.onDexEnd -> "On exit"
            else -> "Off"
        }
        ListItem(
            headlineContent = { Text("DeX") },
            supportingContent = { Text("On exit: the next DeX session starts with a new image") },
            trailingContent = {
                Box {
                    OutlinedButton(onClick = { open = true }) { Text(current) }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        listOf("Off" to (false to false), "On startup" to (true to false), "On exit" to (false to true)).forEach { (label, v) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onChange(r.copy(onDexStart = v.first, onDexEnd = v.second)) })
                        }
                    }
                }
            },
        )
    }
    ListItem(
        headlineContent = { Text("When the screen turns off") },
        supportingContent = {
            Text(
                "The next time you turn the screen on, it shows a new image. Keeps a small background service running; " +
                    "hide its notification by turning off the “Screen watcher” category in Deskmat's notification settings.",
            )
        },
        trailingContent = { Switch(checked = r.onScreenOff, onCheckedChange = { onChange(r.copy(onScreenOff = it)) }) },
    )
    val ctx = LocalContext.current
    if (remember { Screens.foldable(ctx) }) {
        var foldOpen by remember { mutableStateOf(false) }
        val fold = when {
            r.onFold && r.onUnfold -> "On fold and unfold"
            r.onFold -> "On fold"
            r.onUnfold -> "On unfold"
            else -> "Off"
        }
        ListItem(
            headlineContent = { Text("Fold") },
            supportingContent = {
                Text(
                    "Next image when you fold or unfold. The change happens while that screen is put away, so you never see it switch: " +
                        "“On unfold” changes the main screen as you fold, ready for the next time you open the phone. Uses the same background service.",
                )
            },
            trailingContent = {
                Box {
                    OutlinedButton(onClick = { foldOpen = true }) { Text(fold) }
                    DropdownMenu(expanded = foldOpen, onDismissRequest = { foldOpen = false }) {
                        listOf("Off" to (false to false), "On fold" to (true to false), "On unfold" to (false to true), "On fold and unfold" to (true to true)).forEach { (label, v) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { foldOpen = false; onChange(r.copy(onFold = v.first, onUnfold = v.second)) })
                        }
                    }
                }
            },
        )
    }
    ListItem(
        headlineContent = { Text("Interval") },
        supportingContent = {
            Text(
                when (dex) {
                    true -> "While DeX is running. When DeX connects, the image changes if the last one has expired."
                    false -> "Runs all the time for this screen"
                    null -> "DeX screens: while DeX is running. Phone screens: all the time."
                },
            )
        },
        trailingContent = { Switch(checked = r.interval, onCheckedChange = { onChange(r.copy(interval = it)) }) },
    )
    if (r.interval) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15, 30, 60, 180, 360, 720, 1440).forEach { m ->
                FilterChip(selected = r.intervalMin == m, onClick = { onChange(r.copy(intervalMin = m)) }, label = { Text(intervalLabel(m)) })
            }
        }
    }
    ListItem(
        headlineContent = { Text("Minimum time shown") },
        supportingContent = {
            Text(
                "Automatic changes wait until the image has been up this long, so folding or locking a lot doesn't flip through images. " +
                    "At least 30 seconds always applies (2 seconds for Next by hand), against floods of events.",
            )
        },
    )
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0, 1, 5, 15, 30, 60, 180).forEach { m ->
            FilterChip(selected = r.minShowMin == m, onClick = { onChange(r.copy(minShowMin = m)) }, label = { Text(if (m == 0) "30 s" else intervalLabel(m)) })
        }
    }
    val access = remember(r.mustBeSeen) { Seen.hasAccess(ctx) }
    ListItem(
        headlineContent = { Text("Must have been seen") },
        supportingContent = {
            Text(
                "After a change, the next automatic change waits until you've been to the home screen (or seen the lock screen, for lock screen wallpapers). " +
                    if (r.mustBeSeen && !access) "Needs Usage access: tap Allow." else "",
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (r.mustBeSeen && !access) {
                    TextButton(onClick = { ctx.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text("Allow") }
                }
                Switch(checked = r.mustBeSeen, onCheckedChange = { onChange(r.copy(mustBeSeen = it)) })
            }
        },
    )
}

@Composable
private fun PermissionRow(title: String, why: String, granted: Boolean, onGrant: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(why) },
        trailingContent = { if (granted) Text("Allowed", color = MaterialTheme.colorScheme.primary) else FilledTonalButton(onClick = onGrant) { Text("Allow") } },
    )
}

@Composable
private fun Code(text: String) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        SelectionContainer {
            Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
        }
    }
}


private val DOWNSAMPLE_OPTIONS = listOf("off", "dex", "3840", "2560", "1920", "1280")

@Composable
private fun DownsampleRow(state: LibraryState, onChange: (String) -> Unit, dex: Boolean) {
    var open by remember { mutableStateOf(false) }
    fun label(v: String) = when (v) {
        "off" -> "Off (Android scales the original)"
        "dex" -> if (dex) "Match each screen (DeX ${state.lastDexWidth} px)" else "Match the screen"
        else -> "$v px wide (DeX)"
    }
    val options = if (dex) DOWNSAMPLE_OPTIONS else listOf("off", "dex")
    ListItem(
        headlineContent = { Text("Pre-scale images") },
        supportingContent = {
            Text(
                "Crops and shrinks each image to its screen's size on the phone, once, with high-quality filtering, and keeps the result. " +
                    "Android then handles a screen-sized picture instead of decoding the full original on every change (much less memory and work), " +
                    "and shows it 1:1 without scaling artifacts. Images with a landscape crop are always sent whole.",
            )
        },
        trailingContent = {
            Box {
                OutlinedButton(onClick = { open = true }) { Text(label(state.downsample)) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    options.forEach { v ->
                        DropdownMenuItem(text = { Text(label(v)) }, onClick = { open = false; onChange(v) })
                    }
                }
            }
        },
    )
}

/** Apps allowed to read or control Deskmat through its remote API, with a way to take it back. */
@Composable
private fun ConnectedApps() {
    val ctx = LocalContext.current
    val grants by RemoteAccess.grants.collectAsState()
    LaunchedEffect(Unit) { RemoteAccess.list(ctx) }
    SectionTitle("Connected apps")
    Text(
        "Apps you allowed to see your wallpapers, groups and flags, and (with control) change them. " +
            "Apps ask through Deskmat's remote API (docs/REMOTE_API.md); you decide in a dialog.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (grants.isEmpty()) ListItem(headlineContent = { Text("No apps connected") })
    grants.forEach { g ->
        val label = remember(g.pkg) { RemoteAccess.label(ctx, g.pkg) }
        ListItem(
            headlineContent = { Text(label) },
            supportingContent = {
                Text(
                    (if (label != g.pkg) g.pkg + " · " else "") + (if (g.scope == Scope.CONTROL) "Read and control" else "Read only") +
                        (if (g.lastUsed > 0) " · last used " + android.text.format.DateUtils.getRelativeTimeSpanString(g.lastUsed) else ""),
                )
            },
            trailingContent = { TextButton(onClick = { RemoteAccess.revoke(ctx, g.pkg) }) { Text("Remove") } },
        )
    }
}
