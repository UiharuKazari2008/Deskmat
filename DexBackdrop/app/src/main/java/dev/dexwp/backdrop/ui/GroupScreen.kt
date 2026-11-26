package dev.dexwp.backdrop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.ui.text.font.FontFamily
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.dexwp.backdrop.engine.Rules
import dev.dexwp.backdrop.model.AutoRules
import dev.dexwp.backdrop.model.Condition
import dev.dexwp.backdrop.model.Group
import dev.dexwp.backdrop.model.Target
import dev.dexwp.backdrop.model.RuleGroup

/** Where a condition dialog writes its result: rule group index, and condition index (null = new). */
private data class EditSlot(val group: Int, val index: Int?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreen(
    g: Group,
    count: Int,
    active: Boolean,
    knownFlags: Set<String>,
    defaults: AutoRules,
    /** 1-based priority and the number of groups in the slot (null for Unassigned). */
    priority: Pair<Int, Int>?,
    onBack: () -> Unit,
    onUpdate: ((Group) -> Group) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    /** False on phones without DeX: the DeX start/end rules are hidden. */
    showDex: Boolean,
    onPinShortcut: () -> Unit,
    /** Slots on other screens this group can be linked to. */
    linkable: List<Target> = emptyList(),
) {
    var editing by remember { mutableStateOf<EditSlot?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            AppTopBar(
                title = g.name,
                navigationIcon = { BarIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) },
                actions = { if (!g.isUnassigned) BarPillButton(Icons.Default.Delete, "Delete group", danger = true) { confirmDelete = true } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
            Text(
                (if (g.global) "Every screen (made on the ${g.target.phrase})" else g.target.label) +
                    " · $count image${if (count == 1) "" else "s"}" + if (active) " · active now" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (g.isUnassigned) {
                Text(
                    "Images that aren't in a group. This group is active whenever no other group's triggers match.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                var name by remember(g.id) { mutableStateOf(g.name) }
                OutlinedTextField(
                    value = name, onValueChange = { name = it; onUpdate { gr -> gr.copy(name = it.ifBlank { gr.name }) } },
                    label = { Text("Name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
            }

            ListItem(
                headlineContent = { Text("Shuffle") },
                supportingContent = { Text("Pick a random image each time the group advances. Off: go through the images in the order you arranged them.") },
                leadingContent = { Icon(DiceIcon, null) },
                trailingContent = { Switch(checked = g.shuffle, onCheckedChange = { on -> onUpdate { it.copy(shuffle = on) } }) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.padding(top = 8.dp),
            )

            if (!g.isUnassigned) {
                SectionTitle("Screens")
                ListItem(
                    headlineContent = { Text("On every screen (global)") },
                    supportingContent = {
                        Text(
                            "The group, with these triggers and rules, exists on every screen, but each screen fills it with its own images " +
                                "(add them from that screen's gallery). A screen's own groups always come first.",
                        )
                    },
                    trailingContent = { Switch(checked = g.global, onCheckedChange = { on -> onUpdate { it.copy(global = on) } }) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }
            if (!g.isUnassigned && linkable.isNotEmpty()) {
                SectionTitle("Linked screens")
                Text(
                    "Linked screens share this screen's images instead: they show the same image and change together, " +
                        "and the group comes before their own groups while it is active. Each screen keeps its own crop, set from that screen's gallery.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                linkable.forEach { t ->
                    ListItem(
                        headlineContent = { Text(t.label) },
                        trailingContent = {
                            Switch(checked = t in g.links, onCheckedChange = { on -> onUpdate { it.copy(links = if (on) it.links + t else it.links - t) } })
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    )
                }
            }

            SectionTitle("Automatic")
            Text(
                "While this group is active and Automatic is on, these rules decide when it moves to its next image.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ListItem(
                headlineContent = { Text("Use the default rules") },
                supportingContent = { Text(if (g.auto == null) autoSummary(defaults, g.target.isDex) + " (change in Settings)" else "Off: this group has its own rules") },
                trailingContent = {
                    Switch(checked = g.auto == null, onCheckedChange = { useDefault -> onUpdate { it.copy(auto = if (useDefault) null else defaults) } })
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            )
            g.auto?.let { rules -> AutoRulesEditor(rules, g.target.isDex, showDex) { r -> onUpdate { it.copy(auto = r) } } }

            if (!g.isUnassigned) {
                SectionTitle("Triggers")
                Text(
                    "The group is active when ANY rule group matches. A rule group matches when ALL of its conditions are true. " +
                        "When several groups match, the one higher in the list wins.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                g.triggers.forEachIndexed { gi, group ->
                    if (gi > 0) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            HorizontalDivider(Modifier.weight(1f))
                            Text("  OR  ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            HorizontalDivider(Modifier.weight(1f))
                        }
                    }
                    OutlinedCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Rule ${gi + 1}: all of", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onUpdate { it.copy(triggers = it.triggers.filterIndexed { i, _ -> i != gi }) } }) {
                                Icon(Icons.Default.Delete, "Delete rule")
                            }
                        }
                        group.conditions.forEachIndexed { ci, c ->
                            if (ci > 0) Text("AND", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 56.dp))
                            ListItem(
                                headlineContent = { Text(Rules.describe(c)) },
                                leadingContent = { Icon(iconFor(c), null) },
                                trailingContent = {
                                    IconButton(onClick = { onUpdate { it.withRule(gi) { r -> r.copy(conditions = r.conditions.filterIndexed { i, _ -> i != ci }) } } }) {
                                        Icon(Icons.Default.Close, "Remove")
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                        TextButton(onClick = { editing = EditSlot(gi, null) }, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)) {
                            Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Add condition")
                        }
                    }
                }
                OutlinedButton(
                    onClick = { onUpdate { it.copy(triggers = it.triggers + RuleGroup()) }; editing = EditSlot(g.triggers.size, null) },
                    modifier = Modifier.padding(top = 12.dp),
                ) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text(if (g.triggers.isEmpty()) "Add trigger" else "Add OR rule") }
                if (g.triggers.isEmpty()) {
                    Text(
                        "Without triggers the group is only shown when you pin one of its images (Set now, a shortcut or a command).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            SectionTitle("Shortcut & command")
            var command by remember(g.id) { mutableStateOf(g.command.orEmpty()) }
            OutlinedTextField(
                value = command,
                onValueChange = { v -> command = v.replace(Regex("\\s"), "-"); onUpdate { it.copy(command = command.ifBlank { null }) } },
                label = { Text("Command name") }, placeholder = { Text("e.g. work") }, singleLine = true,
                supportingText = { Text("Makes this group active until a trigger changes. Used by shortcuts, Modes and Routines, Termux and broadcasts") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (command.isNotBlank()) {
                Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Termux", style = MaterialTheme.typography.labelMedium)
                        Text("dexwp set $command ${g.target.key}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Text("Broadcast (Tasker, adb)", style = MaterialTheme.typography.labelMedium)
                        Text(
                            "am broadcast -n dev.dexwp.backdrop/.system.CommandReceiver -a dev.dexwp.backdrop.COMMAND --es cmd set --es arg $command --es target ${g.target.key}",
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                AssistChip(onClick = onPinShortcut, label = { Text("Add shortcut to home screen") }, leadingIcon = { Icon(Icons.Default.Home, null) }, modifier = Modifier.padding(top = 8.dp))
            }

            if (priority != null) {
                SectionTitle("Priority")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${priority.first} of ${priority.second} groups", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onMove(-1) }, enabled = priority.first > 1) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                    IconButton(onClick = { onMove(1) }, enabled = priority.first < priority.second) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                }
            }
        }
        }
    }

    editing?.let { slot ->
        val initial = slot.index?.let { g.triggers.getOrNull(slot.group)?.conditions?.getOrNull(it) }
        ConditionDialog(
            initial = initial, knownFlags = knownFlags,
            onDismiss = {
                editing = null
                // Drop a rule that was created for this dialog but never got a condition.
                onUpdate { it.copy(triggers = it.triggers.filter { r -> r.conditions.isNotEmpty() }) }
            },
            onSave = { c ->
                editing = null
                onUpdate { gr ->
                    val triggers = if (slot.group >= gr.triggers.size) gr.triggers + RuleGroup() else gr.triggers
                    gr.copy(triggers = triggers).withRule(slot.group) { r ->
                        r.copy(conditions = if (slot.index == null) r.conditions + c else r.conditions.mapIndexed { i, old -> if (i == slot.index) c else old })
                    }
                }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${g.name}”?") },
            text = { Text("Its images move to Unassigned. No images are deleted.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun Group.withRule(i: Int, f: (RuleGroup) -> RuleGroup) =
    copy(triggers = triggers.mapIndexed { gi, r -> if (gi == i) f(r) else r })

private fun iconFor(c: Condition) = when (c) {
    is Condition.Time -> Icons.Default.DateRange
    is Condition.Sun -> Icons.Default.Star
    is Condition.Place -> Icons.Default.LocationOn
    is Condition.Flag -> Icons.Default.Settings
    is Condition.Theme -> DarkModeIcon
}
