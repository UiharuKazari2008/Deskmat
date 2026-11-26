package dev.dexwp.backdrop.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.dexwp.backdrop.engine.Locator
import dev.dexwp.backdrop.engine.Rules
import dev.dexwp.backdrop.engine.SunTimes
import dev.dexwp.backdrop.model.Condition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val TYPES = listOf("Time", "Sun", "Place", "Flag", "Theme")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConditionDialog(initial: Condition?, knownFlags: Set<String>, onDismiss: () -> Unit, onSave: (Condition) -> Unit) {
    var type by remember {
        mutableStateOf(when (initial) { is Condition.Sun -> 1; is Condition.Place -> 2; is Condition.Flag -> 3; is Condition.Theme -> 4; else -> 0 })
    }
    // Time
    var start by remember { mutableStateOf((initial as? Condition.Time)?.startMin ?: 9 * 60) }
    var end by remember { mutableStateOf((initial as? Condition.Time)?.endMin ?: 17 * 60) }
    var days by remember { mutableStateOf((initial as? Condition.Time)?.days ?: (1..5).toSet()) }
    var picking by remember { mutableStateOf<String?>(null) }
    // Sun
    var phases by remember { mutableStateOf((initial as? Condition.Sun)?.phases ?: setOf("night")) }
    // Place
    val place = initial as? Condition.Place
    var label by remember { mutableStateOf(place?.label ?: "Work") }
    var radius by remember { mutableStateOf(place?.radius ?: 200f) }
    var latLon by remember { mutableStateOf(place?.let { it.lat to it.lon }) }
    var locating by remember { mutableStateOf(false) }
    // Flag
    var flag by remember { mutableStateOf((initial as? Condition.Flag)?.name ?: "") }
    var flagOn by remember { mutableStateOf((initial as? Condition.Flag)?.on ?: true) }
    // Theme
    var dark by remember { mutableStateOf((initial as? Condition.Theme)?.dark ?: true) }

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val locate: () -> Unit = {
        locating = true
        scope.launch {
            val l = withContext(Dispatchers.IO) { Locator.current(ctx, allowFresh = true) }
            locating = false
            if (l != null) latLon = l.latitude to l.longitude
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) locate()
    }

    val result: Condition? = when (type) {
        0 -> if (days.isNotEmpty()) Condition.Time(start, end, days) else null
        1 -> if (phases.isNotEmpty()) Condition.Sun(phases) else null
        2 -> latLon?.let { Condition.Place(label.ifBlank { "Place" }, it.first, it.second, radius) }
        4 -> Condition.Theme(dark)
        else -> flag.trim().takeIf { it.isNotEmpty() }?.let { Condition.Flag(it, flagOn) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add condition" else "Edit condition") },
        confirmButton = { TextButton(enabled = result != null, onClick = { result?.let(onSave) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    TYPES.forEachIndexed { i, t ->
                        SegmentedButton(selected = type == i, onClick = { type = i }, shape = SegmentedButtonDefaults.itemShape(i, TYPES.size)) { Text(t) }
                    }
                }
                when (type) {
                    0 -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { picking = "start" }) { Text("From ${Rules.hhmm(start)}") }
                            OutlinedButton(onClick = { picking = "end" }) { Text("To ${Rules.hhmm(end)}") }
                        }
                        if (end <= start) Text("Runs past midnight", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            (1..7).forEach { d ->
                                FilterChip(selected = d in days, onClick = { days = if (d in days) days - d else days + d }, label = { Text(Rules.DAY_LETTERS[d - 1]) })
                            }
                        }
                    }
                    1 -> {
                        Text("Based on sunrise and sunset where you are.", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            SunTimes.PHASES.forEach { p ->
                                FilterChip(selected = p in phases, onClick = { phases = if (p in phases) phases - p else phases + p }, label = { Text(p.replaceFirstChar { it.uppercase() }) })
                            }
                        }
                    }
                    2 -> {
                        OutlinedTextField(label, { label = it }, label = { Text("Place name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("Radius: ${radius.toInt()} m", style = MaterialTheme.typography.bodyMedium)
                        Slider(value = radius, onValueChange = { radius = it }, valueRange = 50f..2000f, steps = 38)
                        FilledTonalButton(enabled = !locating, onClick = {
                            if (Locator.hasPermission(ctx)) locate()
                            else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }) { Text(if (locating) "Locating…" else "Use current location") }
                        Text(
                            latLon?.let { String.format(Locale.US, "%.5f, %.5f", it.first, it.second) } ?: "No location chosen",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (!Locator.hasBackground(ctx)) {
                            Text("Tip: allow location “all the time” in Settings so places switch in the background.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    4 -> {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf(false, true).forEachIndexed { i, d ->
                                SegmentedButton(selected = dark == d, onClick = { dark = d }, shape = SegmentedButtonDefaults.itemShape(i, 2)) {
                                    Text(if (d) "Dark mode" else "Light mode")
                                }
                            }
                        }
                        Text(
                            "Follows the phone's dark mode, including its sunset or custom schedule.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    else -> {
                        OutlinedTextField(flag, { flag = it.replace(Regex("\\s"), "-") }, label = { Text("Flag name") }, placeholder = { Text("e.g. focus") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (knownFlags.isNotEmpty()) {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                knownFlags.sorted().forEach { f -> AssistChip(onClick = { flag = f }, label = { Text(f) }) }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Flag is ${if (flagOn) "on" else "off"}", modifier = Modifier.weight(1f))
                            Switch(checked = flagOn, onCheckedChange = { flagOn = it })
                        }
                        Text("Set flags from Modes and Routines, Termux (dexwp flag name on) or the Flags screen.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
    )

    picking?.let { which ->
        val initialMin = if (which == "start") start else end
        val tp = rememberTimePickerState(initialMin / 60, initialMin % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    val m = tp.hour * 60 + tp.minute
                    if (which == "start") start = m else end = m
                    picking = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
            text = { TimePicker(state = tp, modifier = Modifier.padding(top = 8.dp)) },
        )
    }
}
