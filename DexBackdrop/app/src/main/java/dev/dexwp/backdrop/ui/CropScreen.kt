package dev.dexwp.backdrop.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.dexwp.backdrop.engine.Applier
import dev.dexwp.backdrop.engine.Images
import dev.dexwp.backdrop.model.Crop
import dev.dexwp.backdrop.model.Wallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Steps through a batch of newly added images; [onMove] saves the crop and moves by -1 / +1 (past the end = done). */
class CropPager(val index: Int, val count: Int, val onMove: (Crop, Int) -> Unit)

/**
 * Crop editor: drag inside the frame to move it, drag a corner to resize. The frame keeps the
 * chosen aspect (the screen's by default, [aspectLabel]). The saved rect is the crop hint Android uses.
 */
@Composable
fun CropScreen(
    wp: Wallpaper,
    screenAspect: Float,
    aspectLabel: String,
    onCancel: (() -> Unit)?,
    onSave: (Crop) -> Unit,
    inline: Boolean = false,
    pager: CropPager? = null,
    /** The crop being edited: the portrait one by default, or the landscape one. */
    crop: Crop? = wp.crop,
    /** Save each change shortly after it is made (and on leaving) instead of with a Save button. */
    autoSave: Boolean = false,
) {
    // Auto-saving: the saved crop comes back as [crop]; keep the editor's state instead of resetting to it.
    val stateKey = if (autoSave) null else crop
    val imgW = wp.width.toFloat()
    val imgH = wp.height.toFloat()
    val options = remember(screenAspect, wp.target) {
        val screen = aspectLabel + " " + String.format(Locale.US, "%.2f:1", screenAspect) to screenAspect
        when {
            wp.target.isDex -> listOf(screen, "16:10" to 1.6f, "16:9" to 16f / 9, "21:9" to 21f / 9, "32:9" to 32f / 9, "Free" to null)
            screenAspect >= 1f -> listOf(screen, "16:9" to 16f / 9, "4:3" to 4f / 3, "1:1" to 1f, "Free" to null)
            else -> listOf(screen, "9:16" to 9f / 16, "3:4" to 3f / 4, "1:1" to 1f, "Free" to null)
        }
    }
    val start = crop ?: Applier.centreCrop(wp.width, wp.height, screenAspect)
    var aspectIndex by remember(wp.id, wp.file, stateKey, screenAspect) {
        mutableStateOf(
            if (crop == null) 0
            else options.indexOfFirst { it.second != null && abs(it.second!! - start.width.toFloat() / start.height) < 0.01f }.let { if (it < 0) options.lastIndex else it },
        )
    }
    var l by remember(wp.id, wp.file, stateKey, screenAspect) { mutableFloatStateOf(start.left.toFloat()) }
    var t by remember(wp.id, wp.file, stateKey, screenAspect) { mutableFloatStateOf(start.top.toFloat()) }
    var r by remember(wp.id, wp.file, stateKey, screenAspect) { mutableFloatStateOf(start.right.toFloat()) }
    var b by remember(wp.id, wp.file, stateKey, screenAspect) { mutableFloatStateOf(start.bottom.toFloat()) }
    if (autoSave) {
        // A crop not yet saved; written after a short pause, or when the image or page changes.
        val pending = remember(wp.id, wp.file, screenAspect) { mutableStateOf<Crop?>(null) }
        LaunchedEffect(l, t, r, b) {
            val c = Crop(l.roundToInt(), t.roundToInt(), r.roundToInt(), b.roundToInt())
            if (c == start) return@LaunchedEffect
            pending.value = c
            delay(400)
            onSave(c)
            pending.value = null
        }
        // After an upscale the file changes and the pending crop is in the old image's pixels: dropped.
        val file by rememberUpdatedState(wp.file)
        DisposableEffect(wp.id, wp.file, screenAspect) {
            val save = onSave
            val p = pending
            val f = wp.file
            onDispose { if (file == f) p.value?.let(save) }
        }
    }
    var hoverIcon by remember { mutableStateOf(PointerIcon.Default) }
    val changed = crop == null || l.roundToInt() != start.left || t.roundToInt() != start.top ||
        r.roundToInt() != start.right || b.roundToInt() != start.bottom
    var preview by remember { mutableStateOf(false) }
    val aspect = options[aspectIndex].second

    fun reset(a: Float?) {
        val c = if (a == null) Crop(0, 0, wp.width, wp.height) else Applier.centreCrop(wp.width, wp.height, a)
        l = c.left.toFloat(); t = c.top.toFloat(); r = c.right.toFloat(); b = c.bottom.toFloat()
    }

    val bmp by produceState<Bitmap?>(null, wp.file) { value = withContext(Dispatchers.IO) { Images.preview(wp.file, null, 2048) } }
    val handlePx = with(LocalDensity.current) { 36.dp.toPx() }

    val rootModifier = if (inline) Modifier.fillMaxSize().clip(MaterialTheme.shapes.large).background(Color.Black)
    else Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()
    Column(rootModifier) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
            val boxW = constraints.maxWidth.toFloat()
            val boxH = constraints.maxHeight.toFloat()
            val s = min(boxW / imgW, boxH / imgH)
            val offX = (boxW - imgW * s) / 2
            val offY = (boxH - imgH * s) / 2
            val image = bmp
            if (preview && image != null) {
                val crop = Crop(l.roundToInt(), t.roundToInt(), r.roundToInt(), b.roundToInt())
                WallpaperImage(wp.file, crop, 2048, Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio((r - l) / (b - t)), ContentScale.Fit)
            } else if (image != null) {
                with(LocalDensity.current) {
                    Image(
                        image.asImageBitmap(), null, contentScale = ContentScale.FillBounds,
                        modifier = Modifier.offset { IntOffset(offX.roundToInt(), offY.roundToInt()) }.size((imgW * s).toDp(), (imgH * s).toDp()),
                    )
                }
                Canvas(
                    Modifier.fillMaxSize()
                        .pointerHoverIcon(hoverIcon)
                        // Mouse: cursor shows what a drag will do; the wheel resizes around the centre.
                        .pointerInput(aspect, s) {
                            awaitPointerEventScope {
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val ch = e.changes.firstOrNull() ?: continue
                                    val px = (ch.position.x - offX) / s
                                    val py = (ch.position.y - offY) / s
                                    val tol = handlePx / s
                                    val nearCorner = listOf(l to t, r to t, r to b, l to b).any { (cx, cy) -> abs(cx - px) < tol && abs(cy - py) < tol }
                                    hoverIcon = when {
                                        nearCorner -> PointerIcon.Crosshair
                                        px in l..r && py in t..b -> PointerIcon.Hand
                                        else -> PointerIcon.Default
                                    }
                                    if (e.type == PointerEventType.Scroll) {
                                        val f = 1f + ch.scrollDelta.y * 0.05f
                                        val cx = (l + r) / 2; val cy = (t + b) / 2
                                        var w = ((r - l) * f).coerceAtLeast(64f)
                                        var h = ((b - t) * f).coerceAtLeast(64f)
                                        val k = minOf(1f, imgW / w, imgH / h)
                                        w *= k; h *= k
                                        val nl = (cx - w / 2).coerceIn(0f, imgW - w)
                                        val nt = (cy - h / 2).coerceIn(0f, imgH - h)
                                        l = nl; t = nt; r = nl + w; b = nt + h
                                        ch.consume()
                                    }
                                }
                            }
                        }
                        .pointerInput(aspect, s) {
                        var mode = -1 // 0..3 corners (TL, TR, BR, BL), 4 = move
                        var px = 0f
                        var py = 0f
                        detectDragGestures(
                            onDragStart = { p ->
                                px = (p.x - offX) / s; py = (p.y - offY) / s
                                val tol = handlePx / s
                                val corners = listOf(l to t, r to t, r to b, l to b)
                                mode = corners.indexOfFirst { (cx, cy) -> abs(cx - px) < tol && abs(cy - py) < tol }
                                if (mode < 0 && px in l..r && py in t..b) mode = 4
                            },
                            onDrag = { change, d ->
                                change.consume()
                                px += d.x / s; py += d.y / s
                                if (mode == 4) {
                                    val dx = (d.x / s).coerceIn(-l, imgW - r)
                                    val dy = (d.y / s).coerceIn(-t, imgH - b)
                                    l += dx; r += dx; t += dy; b += dy
                                } else if (mode in 0..3) {
                                    // Anchor is the opposite corner.
                                    val ax = if (mode == 0 || mode == 3) r else l
                                    val ay = if (mode == 0 || mode == 1) b else t
                                    val dirX = if (px < ax) -1f else 1f
                                    val dirY = if (py < ay) -1f else 1f
                                    val maxW = if (dirX < 0) ax else imgW - ax
                                    val maxH = if (dirY < 0) ay else imgH - ay
                                    var w = abs(px - ax).coerceIn(64f, maxW)
                                    var h = abs(py - ay).coerceIn(64f, maxH)
                                    if (aspect != null) {
                                        if (w / h > aspect) w = h * aspect else h = w / aspect
                                        if (w > maxW) { w = maxW; h = w / aspect }
                                        if (h > maxH) { h = maxH; w = h * aspect }
                                    }
                                    val nx = ax + dirX * w
                                    val ny = ay + dirY * h
                                    l = min(ax, nx); r = maxOf(ax, nx); t = min(ay, ny); b = maxOf(ay, ny)
                                }
                            },
                        )
                    },
                ) {
                    val x0 = offX + l * s; val y0 = offY + t * s; val x1 = offX + r * s; val y1 = offY + b * s
                    val dim = Color.Black.copy(alpha = 0.6f)
                    val iw = imgW * s; val ih = imgH * s
                    drawRect(dim, Offset(offX, offY), Size(iw, y0 - offY))
                    drawRect(dim, Offset(offX, y1), Size(iw, offY + ih - y1))
                    drawRect(dim, Offset(offX, y0), Size(x0 - offX, y1 - y0))
                    drawRect(dim, Offset(x1, y0), Size(offX + iw - x1, y1 - y0))
                    for (i in 1..2) {
                        val gx = x0 + (x1 - x0) * i / 3; val gy = y0 + (y1 - y0) * i / 3
                        drawLine(Color.White.copy(alpha = 0.4f), Offset(gx, y0), Offset(gx, y1), 1.dp.toPx())
                        drawLine(Color.White.copy(alpha = 0.4f), Offset(x0, gy), Offset(x1, gy), 1.dp.toPx())
                    }
                    drawRect(Color.White, Offset(x0, y0), Size(x1 - x0, y1 - y0), style = Stroke(2.dp.toPx()))
                    val hl = 18.dp.toPx(); val hw = 4.dp.toPx()
                    listOf(Offset(x0, y0) to (1f to 1f), Offset(x1, y0) to (-1f to 1f), Offset(x1, y1) to (-1f to -1f), Offset(x0, y1) to (1f to -1f)).forEach { (p, d) ->
                        drawLine(Color.White, p, Offset(p.x + d.first * hl, p.y), hw)
                        drawLine(Color.White, p, Offset(p.x, p.y + d.second * hl), hw)
                    }
                }
            }
        }

        Column(Modifier.background(if (inline) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface).padding(16.dp)) {
            if (inline) Text("Drag to move · drag a corner or scroll to resize", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${(r - l).roundToInt()} × ${(b - t).roundToInt()} px of ${wp.width} × ${wp.height}",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                )
                Text("Preview", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                Switch(checked = preview, onCheckedChange = { preview = it })
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEachIndexed { i, (label, a) ->
                    FilterChip(selected = i == aspectIndex, onClick = { aspectIndex = i; reset(a) }, label = { Text(label) })
                }
            }
            val current = { Crop(l.roundToInt(), t.roundToInt(), r.roundToInt(), b.roundToInt()) }
            if (pager != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${wp.name} · ${pager.index + 1} of ${pager.count}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { reset(aspect) }) { Text("Reset") }
                    OutlinedButton(enabled = pager.index > 0, onClick = { pager.onMove(current(), -1) }) { Text("Previous") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { pager.onMove(current(), 1) }) { Text(if (pager.index == pager.count - 1) "Done" else "Next") }
                }
            } else if (autoSave) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (changed && crop != null) "Saving…" else if (crop == null) "Centred crop (not set yet)" else "Saved automatically",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { reset(aspect) }) { Text("Reset") }
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (onCancel != null) TextButton(onClick = onCancel) { Text("Cancel") }
                TextButton(onClick = { reset(aspect) }) { Text("Reset") }
                Spacer(Modifier.width(8.dp))
                Button(enabled = changed, onClick = { onSave(current()) }) { Text("Save crop") }
            }
        }
    }
}

