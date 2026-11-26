package dev.dexwp.backdrop.routines

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.dexwp.backdrop.data.Library
import dev.dexwp.backdrop.model.Condition
import dev.dexwp.backdrop.engine.Screens
import dev.dexwp.backdrop.model.Json
import dev.dexwp.backdrop.model.Target
import dev.dexwp.backdrop.ui.DiceIcon
import dev.dexwp.backdrop.ui.NextIcon
import dev.dexwp.backdrop.ui.BackdropTheme
import dev.dexwp.backdrop.ui.WallpaperImage
import dev.dexwp.backdrop.ui.clipMedium

/**
 * Parameter picker the Routines host opens for our actions/conditions (configuration targetUi).
 * Returns RESULT_OK with "intent_params" (ParameterValues JSON) and "label_params".
 */
class RoutineConfigActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val tag = intent.getStringExtra("tag").orEmpty()
        val current = RoutineParams.parse(intent.getStringExtra("parameterValues"))
        val state = Library.get(this)

        fun finishWith(values: Map<String, Any>) {
            val json = RoutineParams.encode(values)
            val label = RoutineParams.label(this, tag, RoutineParams.parse(json))
            setResult(RESULT_OK, Intent().putExtra("intent_params", json).putExtra("parameterValues", json).putExtra("label_params", label))
            finish()
        }

        setContent {
            BackdropTheme {
                val isFlag = tag == RoutineParams.ACTION_FLAG || tag == RoutineParams.CONDITION_FLAG
                Scaffold(topBar = {
                    TopAppBar(title = {
                        Text(when (tag) {
                            RoutineParams.ACTION_SET -> "Set Deskmat wallpaper"
                            RoutineParams.CONDITION_SHOWING -> "Deskmat wallpaper is showing"
                            RoutineParams.ACTION_FLAG -> "Set Deskmat flag"
                            else -> "Deskmat flag is"
                        })
                    })
                }) { pad ->
                    if (isFlag) {
                        val referenced = state.groups.flatMap { it.triggers }.flatMap { it.conditions }.filterIsInstance<Condition.Flag>().map { it.name }
                        val known = (state.flags.keys + referenced).toSortedSet()
                        var name by remember { mutableStateOf(current["flag"] as? String ?: known.firstOrNull().orEmpty()) }
                        var on by remember { mutableStateOf(current["state"] as? Boolean ?: true) }
                        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(name, { name = it.replace(Regex("\\s"), "-") }, label = { Text("Flag name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                known.forEach { f -> AssistChip(onClick = { name = f }, label = { Text(f) }) }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (on) "On" else "Off", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Switch(checked = on, onCheckedChange = { on = it })
                            }
                            if (tag == RoutineParams.ACTION_FLAG) {
                                Text("When the routine ends, the flag is set back the other way.", style = MaterialTheme.typography.bodySmall)
                            }
                            Button(enabled = name.isNotBlank(), onClick = { finishWith(mapOf("flag" to name.trim(), "state" to on)) }) { Text("Done") }
                        }
                    } else {
                        LazyColumn(Modifier.padding(pad)) {
                            val slots = Target.entries.filter { it.screen in Screens.available(this@RoutineConfigActivity) }
                            if (tag == RoutineParams.ACTION_SET) {
                                items(slots) { t ->
                                    ListItem(
                                        headlineContent = { Text("Automatic") },
                                        supportingContent = { Text("Resume triggers on the ${t.phrase}") },
                                        leadingContent = { Icon(Icons.Default.Refresh, null) },
                                        modifier = Modifier.clickable { finishWith(mapOf("wallpaper" to RoutineParams.AUTO, "target" to t.key)) },
                                    )
                                }
                                items(slots) { t ->
                                    ListItem(
                                        headlineContent = { Text("Next image") },
                                        supportingContent = { Text("Advance the active group on the ${t.phrase}") },
                                        leadingContent = { Icon(NextIcon, null) },
                                        modifier = Modifier.clickable { finishWith(mapOf("wallpaper" to RoutineParams.NEXT, "target" to t.key)) },
                                    )
                                }
                                val groups = slots.flatMap { state.ownGroups(it) }
                                items(groups) { g ->
                                    ListItem(
                                        headlineContent = { Text("Group: ${g.name}") },
                                        supportingContent = { Text("Make it active on the ${g.target.phrase} until a trigger changes") },
                                        leadingContent = { Icon(DiceIcon, null) },
                                        modifier = Modifier.clickable {
                                            finishWith(mapOf("wallpaper" to Json.GROUP_PIN + g.id, "target" to g.target.key, "name" to g.name))
                                        },
                                    )
                                }
                            }
                            items(state.wallpapers) { wp ->
                                ListItem(
                                    headlineContent = { Text(wp.name) },
                                    supportingContent = { Text(wp.target.label + (if (wp.id == current["wallpaper"]) " · selected" else "")) },
                                    leadingContent = { WallpaperImage(wp.file, wp.crop, 256, Modifier.width(96.dp).aspectRatio(16f / 10f).clipMedium()) },
                                    modifier = Modifier.clickable {
                                        finishWith(mapOf("wallpaper" to wp.id, "target" to wp.target.key, "name" to wp.name))
                                    },
                                )
                            }
                            if (state.wallpapers.isEmpty()) {
                                item { Text("Add images in Deskmat first.", modifier = Modifier.padding(16.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
