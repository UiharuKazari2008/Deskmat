package dev.dexwp.backdrop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import dev.dexwp.backdrop.model.Crop
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Button
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Card
import java.io.File
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dexwp.backdrop.model.Group
import dev.dexwp.backdrop.model.Target
import androidx.compose.material3.FilledTonalButton
import dev.dexwp.backdrop.model.Wallpaper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    wp: Wallpaper,
    /** Ids of the user groups this image is in. */
    inGroups: Set<String>,
    /** The slot's user groups. */
    groups: List<Group>,
    onToggleGroup: (Group, Boolean) -> Unit,
    /** Each group the image is in (Unassigned when none) and whether Automatic uses it there. */
    autoGroups: List<Pair<Group, Boolean>>,
    onSkip: (Group, Boolean) -> Unit,
    /** Other slots this image can be copied or moved to. */
    otherSlots: List<Target>,
    onCopyTo: (Target) -> Unit,
    onMoveTo: (Target) -> Unit,
    showing: Boolean,
    onBack: () -> Unit,
    onUpdate: ((Wallpaper) -> Wallpaper) -> Unit,
    onSetNow: () -> Unit,
    pinned: Boolean,
    onUnpin: () -> Unit,
    /** Opens the full-screen crop editor; true = the landscape crop. */
    onEditCrop: (Boolean) -> Unit,
    aspect: Float,
    aspectLabel: String,
    /** Saves a crop; true = the landscape crop. */
    onSaveCrop: (Crop, Boolean) -> Unit,
    /** Phone screens: also crop for landscape (One UI keeps both). */
    hasLandscape: Boolean,
    onPinShortcut: () -> Unit,
    onDelete: () -> Unit,
    /** Set when the image is opened from another screen's linked group: the slot it belongs to. */
    linkedFrom: Target? = null,
    /** Position in the group it was opened from (1-based) and the group's size and name, for Previous / Next. */
    position: Triple<Int, Int, String>? = null,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    /** How much the crop is stretched to fill its screen (1 = pixel for pixel). */
    stretch: Float = 1f,
    /** Upscaling progress 0–1 while it runs, else null. */
    upscaling: Float? = null,
    /** Null when the image can't be upscaled (already large). */
    onUpscale: (() -> Unit)? = null,
    /** Also save upscaled images next to their originals. */
    saveUpscaled: Boolean = false,
    onSaveUpscaled: (Boolean) -> Unit = {},
    /** Set for upscaled images: saves a copy next to the original. */
    onSaveCopy: (() -> Unit)? = null,
) {
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            AppTopBar(
                title = wp.name,
                navigationIcon = { BarIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) },
                actions = { BarPillButton(Icons.Default.Delete, "Delete", danger = true) { confirmDelete = true } },
            )
        },
    ) { pad ->
        // Wide windows (DeX): crop editor on the left, settings on the right.
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        Row(Modifier.fillMaxSize()) {
        var landscape by remember(wp.id) { mutableStateOf(false) }
        if (wide) {
            Column(Modifier.weight(1.3f).fillMaxHeight().padding(start = 16.dp, top = 8.dp, bottom = 16.dp)) {
                if (hasLandscape) OrientationRow(landscape, wp.cropLand != null) { landscape = it }
                Box(Modifier.weight(1f)) {
                    if (landscape) CropScreen(wp, 1f / aspect, "$aspectLabel landscape", onCancel = null, onSave = { onSaveCrop(it, true) }, inline = true, crop = wp.cropLand, autoSave = true)
                    else CropScreen(wp, aspect, aspectLabel, onCancel = null, onSave = { onSaveCrop(it, false) }, inline = true, autoSave = true)
                }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
        ) {
            position?.let { (i, n, group) ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(enabled = i > 1, onClick = onPrevious) { Text("Previous") }
                    Text(
                        "$i of $n · $group", style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Button(enabled = i < n, onClick = onNext) { Text("Next") }
                }
            }
            val crop = wp.crop
            val shown = if (crop != null) crop.width.toFloat() / crop.height else aspect
            if (!wide) Card(Modifier.fillMaxWidth().then(if (shown < 1f) Modifier.padding(horizontal = 48.dp) else Modifier)) {
                WallpaperImage(wp.file, crop, 1600, Modifier.fillMaxWidth().aspectRatio(shown))
            }
            Text(
                (if (crop == null) "No crop set yet: a centred crop for the screen is used. " else "Crop ${crop.width}×${crop.height} of ${wp.width}×${wp.height}. ") +
                    if (showing) "Currently showing." else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (upscaling != null || stretch >= dev.dexwp.backdrop.engine.Upscaler.LOW_RES) LowResCard(stretch, upscaling, onUpscale, wp, saveUpscaled, onSaveUpscaled)
            if (onSaveCopy != null) Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Upscaled 4× with Real-ESRGAN." + (wp.source?.let { " Original: ${it.removePrefix("/storage/emulated/0/")}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onSaveCopy, modifier = Modifier.padding(start = 8.dp)) { Text("Save copy") }
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSetNow, enabled = !pinned) { Icon(PushPinIcon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (pinned) "Pinned" else "Set now") }
                if (!wide) OutlinedButton(onClick = { onEditCrop(false) }) { Icon(Icons.Default.Edit, null); Spacer(Modifier.width(8.dp)); Text("Edit crop") }
                if (!wide && hasLandscape) OutlinedButton(onClick = { onEditCrop(true) }) { Text("Landscape crop") }
            }
            if (hasLandscape) Text(
                wp.cropLand?.let { "Landscape crop ${it.width}×${it.height}, used when the screen is turned sideways." }
                    ?: "Landscape: Android picks it from the portrait crop. Set one to choose it yourself.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            Text(
                "Set now pins this image: triggers and Automatic for the ${wp.target.phrase} pause until a trigger starts or ends, or until you unpin.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (pinned) PinnedBanner(wp.target.phrase, wp.name, onUnpin, Modifier.padding(top = 12.dp))

            var name by remember(wp.id) { mutableStateOf(wp.name) }
            OutlinedTextField(
                value = name, onValueChange = { name = it; onUpdate { w -> w.copy(name = it.ifBlank { w.name }) } },
                label = { Text("Name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )

            if (linkedFrom != null) Text(
                "Shown here through a linked group from the ${linkedFrom.phrase}. The crop on this page is the ${wp.target.phrase}'s own; " +
                    "groups and order are managed on the ${linkedFrom.phrase}.",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp),
            ) else GroupChips(inGroups, groups, onToggleGroup)
            autoGroups.forEach { (g, used) ->
                ListItem(
                    headlineContent = { Text("Use in Automatic" + if (autoGroups.size > 1 || !g.isUnassigned) " (${g.name})" else "") },
                    supportingContent = { Text(if (used) "Automatic and Next can choose it" else "Skipped by Automatic and Next; Set now still works") },
                    leadingContent = { Icon(AutoIcon, null) },
                    trailingContent = { Switch(checked = used, onCheckedChange = { on -> onSkip(g, !on) }) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }

            MoveCopy(wp, otherSlots, onCopyTo, onMoveTo)

            SectionTitle("Shortcut & command")
            var command by remember(wp.id) { mutableStateOf(wp.command.orEmpty()) }
            OutlinedTextField(
                value = command,
                onValueChange = { v -> command = v.replace(Regex("\\s"), "-"); onUpdate { it.copy(command = command.ifBlank { null }) } },
                label = { Text("Command name") }, placeholder = { Text("e.g. work") }, singleLine = true,
                supportingText = { Text("Used by app shortcuts, Modes and Routines, Termux and broadcasts") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (command.isNotBlank()) {
                Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Termux", style = MaterialTheme.typography.labelMedium)
                        Text("dexwp set $command", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Text("Broadcast (Tasker, adb)", style = MaterialTheme.typography.labelMedium)
                        Text(
                            "am broadcast -n dev.dexwp.backdrop/.system.CommandReceiver -a dev.dexwp.backdrop.COMMAND --es cmd set --es arg $command",
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                AssistChip(onClick = onPinShortcut, label = { Text("Add shortcut to home screen") }, leadingIcon = { Icon(Icons.Default.Home, null) }, modifier = Modifier.padding(top = 8.dp))
            }
        }
        }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${wp.name}”?") },
            text = { Text("The image is removed from the gallery. The wallpaper on screen is not changed until another one applies.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Copy or move the image to another display, or between its home and lock screen. */
@Composable
private fun MoveCopy(wp: Wallpaper, slots: List<Target>, onCopy: (Target) -> Unit, onMove: (Target) -> Unit) {
    if (slots.isEmpty()) return
    var slot by remember(wp.id, wp.target) { mutableStateOf(slots.first()) }
    var open by remember { mutableStateOf(false) }
    SectionTitle("Copy or move")
    Text(
        "Now on the ${wp.target.phrase}. A copy is a separate entry (same image file, its own crop and groups). " +
            "Moving to another display resets the crop to that screen's shape.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            OutlinedButton(onClick = { open = true }) { Text(slot.label) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                slots.forEach { t -> DropdownMenuItem(text = { Text(t.label) }, onClick = { open = false; slot = t }) }
            }
        }
        FilledTonalButton(onClick = { onCopy(slot) }) { Text("Copy") }
        TextButton(onClick = { onMove(slot) }) { Text("Move") }
    }
}

/** Portrait / Landscape switch above the inline crop editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrientationRow(landscape: Boolean, landSet: Boolean, onChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        listOf(false, true).forEachIndexed { i, land ->
            SegmentedButton(selected = landscape == land, onClick = { onChange(land) }, shape = SegmentedButtonDefaults.itemShape(i, 2)) {
                Text(if (land) "Landscape" + if (landSet) "" else " (automatic)" else "Portrait")
            }
        }
    }
}

/** One chip per group; an image can be in any number of them (none = Unassigned). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupChips(inGroups: Set<String>, groups: List<Group>, onToggle: (Group, Boolean) -> Unit) {
    SectionTitle("Groups")
    Text(
        if (inGroups.isEmpty()) "In no group, so it is in Unassigned. Tap groups to add it; an image can be in several."
        else "Tap to add or remove. Triggers, shuffle and Automatic are set per group.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (groups.isEmpty()) {
        Text("No groups on this screen yet.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    }
    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        groups.forEach { g ->
            val on = g.id in inGroups
            FilterChip(
                selected = on, onClick = { onToggle(g, !on) }, label = { Text(g.name) },
                leadingIcon = if (on) ({ Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }) else null,
            )
        }
    }
}

/** Shown when the crop is stretched to fill the screen: how much, and an AI upscale button. */
@Composable
private fun LowResCard(stretch: Float, progress: Float?, onUpscale: (() -> Unit)?, wp: Wallpaper, save: Boolean, onSave: (Boolean) -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Low resolution", style = MaterialTheme.typography.titleSmall)
            Text(
                if (progress != null) "Upscaling with Real-ESRGAN (anime) on the phone… ${(progress * 100).toInt()}%"
                else "This crop is stretched ${"%.1f".format(stretch)}× to fill the ${wp.target.phrase}, so it looks soft. " +
                    if (onUpscale != null) "Upscaling makes the image 4× larger with Real-ESRGAN (anime model), on the phone; crops are kept."
                    else "It's too large to upscale on the phone (${wp.width}×${wp.height}); use a bigger original instead.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
            )
            if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            else if (onUpscale != null) {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = save, onCheckedChange = onSave)
                    Text(
                        "Also save the upscaled image next to the original" + (wp.source?.let { " (${File(it).parent?.removePrefix("/storage/emulated/0/")})" } ?: ", or in Pictures/Deskmat"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                FilledTonalButton(onClick = onUpscale, modifier = Modifier.padding(top = 4.dp)) { Text("Upscale 4×") }
            }
        }
    }
}
