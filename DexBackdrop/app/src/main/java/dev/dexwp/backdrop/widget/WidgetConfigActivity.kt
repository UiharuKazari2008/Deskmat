package dev.dexwp.backdrop.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.dexwp.backdrop.data.Library
import dev.dexwp.backdrop.data.WidgetStyle
import dev.dexwp.backdrop.engine.Screens
import dev.dexwp.backdrop.model.Target
import dev.dexwp.backdrop.ui.BackdropTheme
import dev.dexwp.backdrop.ui.screenIcon
import kotlin.math.roundToInt

/**
 * A widget's settings: what it changes (one screen, all home screens, all lock screens or every
 * screen), and its look like One UI's own widget settings (colours and opacity), plus the
 * slider's label and next-image preview. Opens when the widget is placed (optional) and from the
 * launcher's long-press "Widget settings".
 */
class WidgetConfigActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()
        // Leaving without a choice keeps the widget as it is.
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        Library.init(this)
        val screens = Screens.available(this)
        val foldable = Screens.foldable(this)
        val current = Widgets.target(this, id)
        val slider = Widgets.isSlider(this, id)
        val style = Widgets.styleOf(this, id) ?: WidgetStyle.ONEUI
        setContent {
            BackdropTheme {
                var scope by remember { mutableStateOf(Widgets.scope(this, id)) }
                var screen by remember { mutableStateOf(current.screen) }
                var lock by remember { mutableStateOf(current.lock) }
                var preview by remember { mutableStateOf(Widgets.showsPreview(this, id)) }
                var label by remember { mutableStateOf(Widgets.showsLabel(this, id)) }
                var colors by remember { mutableStateOf(Widgets.colors(this, id)) }
                var opacity by remember { mutableIntStateOf(Widgets.opacity(this, id, style)) }
                var blur by remember { mutableStateOf(Widgets.blurs(this, id, style)) }
                Scaffold(topBar = { TopAppBar(title = { Text("Widget settings") }) }) { pad ->
                    Column(
                        Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Heading("Changes")
                        Widgets.Scope.entries.forEach { s ->
                            Row(
                                Modifier.fillMaxWidth().selectable(selected = scope == s, onClick = { scope = s }, role = Role.RadioButton),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = scope == s, onClick = null)
                                Text(s.label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        if (scope == Widgets.Scope.ONE) {
                            if (screens.size > 1) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                screens.forEachIndexed { i, s ->
                                    SegmentedButton(
                                        selected = screen == s, onClick = { screen = s },
                                        shape = SegmentedButtonDefaults.itemShape(i, screens.size),
                                        icon = { Icon(screenIcon(s, foldable), null, Modifier.size(18.dp)) },
                                    ) { Text(s.short) }
                                }
                            }
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                listOf(false, true).forEachIndexed { i, l ->
                                    SegmentedButton(
                                        selected = lock == l, onClick = { lock = l },
                                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                                        icon = { Icon(if (l) Icons.Default.Lock else Icons.Default.Home, null, Modifier.size(18.dp)) },
                                    ) { Text(if (l) "Lock screen" else "Home screen") }
                                }
                            }
                            Text(
                                "Now: ${Target.of(screen, lock).label}.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (slider) {
                            Heading("Slider")
                            SwitchRow("Show text", "“Next on …” or “Shuffle …” inside the track", label) { label = it }
                            SwitchRow("Show the next image", "A small preview at the end of the track; slide onto it to show it", preview) { preview = it }
                        }

                        Heading("Colors")
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            val order = listOf(Widgets.Colors.LIGHT, Widgets.Colors.DARK, Widgets.Colors.AUTO)
                            order.forEachIndexed { i, cl ->
                                SegmentedButton(selected = colors == cl, onClick = { colors = cl }, shape = SegmentedButtonDefaults.itemShape(i, order.size)) {
                                    Text(if (cl == Widgets.Colors.AUTO) "Match phone" else cl.label)
                                }
                            }
                        }
                        if (style == WidgetStyle.ONEUI) SwitchRow(
                            "Blur the wallpaper behind", "One UI's own background blur, as on Samsung widgets. Opacity below 100% lets it show through",
                            blur,
                        ) { blur = it }
                        Heading("Opacity: $opacity%")
                        Slider(value = opacity.toFloat(), onValueChange = { opacity = it.roundToInt() }, valueRange = 0f..100f)

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { finish() }) { Text("Cancel") }
                            Button(onClick = {
                                if (scope == Widgets.Scope.ONE) Widgets.setTarget(this@WidgetConfigActivity, id, Target.of(screen, lock))
                                else Widgets.setScope(this@WidgetConfigActivity, id, scope)
                                Widgets.setShowsPreview(this@WidgetConfigActivity, id, preview)
                                Widgets.saveLook(this@WidgetConfigActivity, id, colors, opacity, label)
                                Widgets.setBlurs(this@WidgetConfigActivity, id, blur)
                                Widgets.refresh(this@WidgetConfigActivity)
                                finish()
                            }) { Text("Save") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
