package dev.dexwp.backdrop.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.SizeF
import android.util.TypedValue
import android.widget.RemoteViews
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import dev.dexwp.backdrop.engine.Images
import dev.dexwp.backdrop.model.Wallpaper
import dev.dexwp.backdrop.R
import dev.dexwp.backdrop.data.Library
import dev.dexwp.backdrop.data.WidgetStyle
import dev.dexwp.backdrop.engine.Commands
import dev.dexwp.backdrop.engine.Engine
import dev.dexwp.backdrop.engine.Screens
import android.view.Display
import android.content.res.ColorStateList
import android.content.res.Configuration
import dev.dexwp.backdrop.model.Screen
import dev.dexwp.backdrop.model.Target
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Home screen widgets, each in a Material and a One UI style:
 * - Next image (1×1, resizable): tap to advance the slot's active group. Shows fast-forward for a
 *   group in order and a die for a shuffling group; the die rolls before the image changes.
 * - Slide to change (3×1 or 1×3): drag the thumb to the end of the track to advance.
 * Each widget controls one slot (the main screen unless set in its configuration).
 */
object Widgets {
    const val ACTION_NEXT = "dev.dexwp.backdrop.widget.NEXT"
    const val ACTION_SLIDE = "dev.dexwp.backdrop.widget.SLIDE"
    private const val PREFS = "widgets"

    enum class Kind { NEXT, SLIDE }

    private val providers = mapOf(
        NextMaterialWidget::class.java to (Kind.NEXT to WidgetStyle.MATERIAL),
        NextOneUiWidget::class.java to (Kind.NEXT to WidgetStyle.ONEUI),
        SlideMaterialWidget::class.java to (Kind.SLIDE to WidgetStyle.MATERIAL),
        SlideOneUiWidget::class.java to (Kind.SLIDE to WidgetStyle.ONEUI),
    )

    /** Track lengths with a generated layout (tools/gen_widgets.py). */
    private val SLIDE_LENGTHS = (120..420 step 20).toList()

    /** The sizes (dp) the launcher says it shows the widget at; at least one guess when it reports none. */
    private fun reportedSizes(c: Context, id: Int): List<SizeF> {
        val o = AppWidgetManager.getInstance(c).getAppWidgetOptions(id)
        val sizes = runCatching {
            o.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
        }.getOrNull().orEmpty().take(8)
        if (sizes.isNotEmpty()) return sizes
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 250
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 72
        return listOf(SizeF(w.toFloat(), h.toFloat()))
    }

    /**
     * Gap around the knob, as a share of the widget's short side. Samsung's own capsule widgets
     * (Voice Recorder) inset their round button by about a ninth of the height, so it is about
     * three-quarters of it.
     */
    private const val GAP_SHARE = 0.11f
    private val THICKNESSES = listOf(80, 72, 64, 56, 48, 40)

    /** Smallest label size before the track shows chevrons instead (the label auto-shrinks to this). */
    private const val LABEL_MIN_SP = 12f

    /**
     * The slider layout for a widget [size]: the track runs along the longer side, as thick as
     * leaves [TRACK_GAP] above and below it (8 dp steps), and exactly as long as leaves the same gap
     * at its ends. Same rule on every screen, so DeX and phone widgets look alike.
     */
    private fun slideFor(c: Context, id: Int, cls: Class<*>, style: WidgetStyle, size: SizeF, shuffle: Boolean, pad: Float, t: Target, preview: Bitmap?): RemoteViews {
        val across = minOf(size.width, size.height) - pad * 2
        val along = maxOf(size.width, size.height) - pad * 2
        val target = maxOf(6f, across * GAP_SHARE)
        val thick = THICKNESSES.firstOrNull { it <= across - target * 2 } ?: THICKNESSES.last()
        val gap = ((across - thick) / 2).coerceAtLeast(2f)
        val trackLen = (along - gap * 2).coerceAtLeast(thick * 2f)
        // The knob's travel comes from the generated layout (20 dp steps), the track's length is exact.
        val len = SLIDE_LENGTHS.lastOrNull { it <= trackLen } ?: SLIDE_LENGTHS.first()
        // Room for the label between the knob and the preview (or the track's end).
        val room = trackLen - (thick + 6) - (if (preview != null) thick - 8 + 14f else 14f)
        val label = (if (shuffle) "Shuffle " else "Next on ") + slotName(c, id, t)
        val fits = Paint().apply { textSize = LABEL_MIN_SP; typeface = Typeface.DEFAULT_BOLD }.measureText(label) <= room
        val chevrons = !fits || !showsLabel(c, id)
        return slideView(c, id, cls, style, if (size.width >= size.height) "h" else "v", len, "_$thick", shuffle, pad, t, preview, chevrons).apply {
            setViewLayoutWidth(R.id.track, trackLen, TypedValue.COMPLEX_UNIT_DIP)
        }
    }

    /** What a widget changes: one slot, or every home screen, every lock screen, or every screen. */
    enum class Scope(val key: String, val label: String, val short: String) {
        ONE("one", "One screen", ""),
        ALL_HOME("allhome", "All home screens", "all home screens"),
        ALL_LOCK("alllock", "All lock screens", "all lock screens"),
        ALL("all", "Every screen", "every screen"),
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun scope(c: Context, id: Int): Scope = Scope.entries.firstOrNull { it.key == prefs(c).getString("t_$id", null) } ?: Scope.ONE

    fun setScope(c: Context, id: Int, s: Scope) {
        if (s != Scope.ONE) prefs(c).edit().putString("t_$id", s.key).apply()
    }

    /** Every slot the widget advances. */
    fun targets(c: Context, id: Int): List<Target> {
        val screens = Screens.available(c)
        val slots = Target.entries.filter { it.screen in screens }
        return when (scope(c, id)) {
            Scope.ONE -> listOf(target(c, id))
            Scope.ALL_HOME -> slots.filter { !it.lock }
            Scope.ALL_LOCK -> slots.filter { it.lock }
            Scope.ALL -> slots
        }
    }

    /**
     * The slot a widget shows (icon, preview): the one chosen in its settings, else the wallpaper
     * of the home screen it sits on (One UI's launcher reports "hsMode" = "Dex" for DeX, and the
     * display id). For a multi-screen scope, that screen's home or lock slot.
     */
    fun target(c: Context, id: Int): Target {
        val screens = Screens.available(c)
        Target.of(prefs(c).getString("t_$id", null))?.takeIf { it.screen in screens }?.let { return it }
        val here = placedOn(c, id)
        return when (scope(c, id)) {
            Scope.ALL_LOCK -> Target.of(here, lock = true)
            else -> Target.of(here, lock = false)
        }
    }

    private fun placedOn(c: Context, id: Int): Screen {
        val screens = Screens.available(c)
        val o = AppWidgetManager.getInstance(c).getAppWidgetOptions(id)
        val mode = o.getString("hsMode").orEmpty()
        val display = o.getInt("appWidgetDisplayId", Display.DEFAULT_DISPLAY)
        val screen = when {
            mode.equals("Dex", true) || display != Display.DEFAULT_DISPLAY -> Screen.DESKTOP
            mode.contains("cover", true) || mode.contains("sub", true) || mode.contains("front", true) -> Screen.COVER
            else -> Screen.MAIN
        }.takeIf { it in screens } ?: Screen.MAIN
        return screen
    }

    // ---- Look, like One UI's own widget settings: colours (light, dark, match phone) and opacity ----

    enum class Colors(val key: String, val label: String) { AUTO("auto", "Match phone setting"), LIGHT("light", "Light"), DARK("dark", "Dark") }

    fun colors(c: Context, id: Int) = Colors.entries.firstOrNull { it.key == prefs(c).getString("c_$id", null) } ?: Colors.AUTO
    fun opacity(c: Context, id: Int, style: WidgetStyle) = prefs(c).getInt("o_$id", if (style == WidgetStyle.ONEUI) 70 else 100)
    fun showsLabel(c: Context, id: Int) = prefs(c).getBoolean("x_$id", true)

    /** One UI's own wallpaper blur behind the widget (One UI style only; on by default). */
    fun blurs(c: Context, id: Int, style: WidgetStyle) = style == WidgetStyle.ONEUI && prefs(c).getBoolean("b_$id", true)

    fun setBlurs(c: Context, id: Int, on: Boolean) {
        prefs(c).edit().putBoolean("b_$id", on).apply()
    }

    fun saveLook(c: Context, id: Int, colors: Colors, opacity: Int, label: Boolean) {
        prefs(c).edit().putString("c_$id", colors.key).putInt("o_$id", opacity).putBoolean("x_$id", label).apply()
    }

    fun styleOf(c: Context, id: Int): WidgetStyle? = AppWidgetManager.getInstance(c).getAppWidgetInfo(id)?.provider?.className
        ?.let { name -> providers.entries.firstOrNull { it.key.name == name }?.value?.second }

    /** The app's resources in light or dark mode, to read the widget colours of either. */
    private fun themed(c: Context, dark: Boolean): Context {
        val cfg = Configuration(c.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        return c.createConfigurationContext(cfg)
    }

    /**
     * Tints [viewId] via [method] with colour [res]: fixed for Light or Dark, or a light/dark pair
     * the launcher switches with the phone's mode. [alpha] (0–100) applies to backgrounds.
     */
    private fun RemoteViews.tint(c: Context, viewId: Int, method: String, res: Int, colors: Colors, alpha: Int? = null) {
        fun csl(dark: Boolean): ColorStateList {
            val col = themed(c, dark).getColor(res)
            return ColorStateList.valueOf(if (alpha == null) col else (col and 0x00FFFFFF) or ((alpha * 255 / 100) shl 24))
        }
        when (colors) {
            Colors.AUTO -> setColorStateList(viewId, method, csl(false), csl(true))
            Colors.LIGHT -> setColorStateList(viewId, method, csl(false))
            Colors.DARK -> setColorStateList(viewId, method, csl(true))
        }
    }

    private fun RemoteViews.applyLook(c: Context, id: Int, style: WidgetStyle, slider: Boolean) {
        val m = style == WidgetStyle.MATERIAL
        val colors = colors(c, id)
        if (blurs(c, id, style)) {
            // One UI Home blurs the wallpaper behind a semi-transparent plain colour on @android:id/background
            // (alpha must be 1–254; 0 or 255 turns the blur off). The launcher rounds the corners itself.
            val alpha = (opacity(c, id, style) * 255 / 100).coerceIn(1, 254)
            fun bg(dark: Boolean) = (themed(c, dark).getColor(R.color.widget_o_bg) and 0x00FFFFFF) or (alpha shl 24)
            when (colors) {
                Colors.AUTO -> setColorInt(android.R.id.background, "setBackgroundColor", bg(false), bg(true))
                Colors.LIGHT -> setInt(android.R.id.background, "setBackgroundColor", bg(false))
                Colors.DARK -> setInt(android.R.id.background, "setBackgroundColor", bg(true))
            }
        } else {
            tint(c, android.R.id.background, "setBackgroundTintList", if (m) R.color.widget_m_bg else R.color.widget_o_bg, colors, opacity(c, id, style))
        }
        if (slider) {
            tint(c, R.id.track, "setBackgroundTintList", if (m) R.color.widget_m_track else R.color.widget_o_track, colors)
            tint(c, R.id.hint, "setTextColor", if (m) R.color.widget_m_fg else R.color.widget_o_fg, colors)
        } else {
            tint(c, R.id.icon, "setImageTintList", if (m) R.color.widget_m_fg else R.color.widget_o_fg, colors)
        }
    }

    fun setTarget(c: Context, id: Int, t: Target) {
        prefs(c).edit().putString("t_$id", t.key).apply()
    }

    fun forget(c: Context, ids: IntArray) {
        val e = prefs(c).edit()
        ids.forEach { id -> listOf("t_", "p_", "c_", "o_", "x_", "b_").forEach { e.remove("$it$id") } }
        e.apply()
    }

    /** Words for the track: "Desktop", "Main lock", or the scope ("all home screens"). */
    private fun slotName(c: Context, id: Int, t: Target): String =
        scope(c, id).short.ifEmpty { t.screen.short + if (t.lock) " lock" else "" }

    /** Asks the launcher to place a widget (it shows its own confirmation). */
    fun requestPin(c: Context, kind: Kind, style: WidgetStyle): Boolean {
        val awm = AppWidgetManager.getInstance(c) ?: return false
        val cls = providers.entries.first { it.value == (kind to style) }.key
        return awm.isRequestPinAppWidgetSupported && awm.requestPinAppWidget(ComponentName(c, cls), null, null)
    }

    private val refresher by lazy { Handler(HandlerThread("deskmat-widgets").apply { start() }.looper) }
    private var refreshApp: Context? = null
    private val refreshNow = Runnable { refreshApp?.let(::redraw) }

    /**
     * Redraws every placed widget (after a library change or a settings change), on a background
     * thread and at most every 250 ms: building a slider can decode a thumbnail, and library
     * changes often come from the UI thread, several at a time.
     */
    fun refresh(c: Context) {
        refreshApp = c.applicationContext
        refresher.removeCallbacks(refreshNow)
        refresher.postDelayed(refreshNow, 250)
    }

    private fun redraw(app: Context) {
        val awm = AppWidgetManager.getInstance(app) ?: return
        for ((cls, spec) in providers) {
            for (id in awm.getAppWidgetIds(ComponentName(app, cls))) awm.updateAppWidget(id, build(app, id, spec.first, spec.second, cls))
        }
    }

    private fun shuffling(c: Context, t: Target): Boolean {
        val s = Library.get(c)
        return (s.pinnedGroup(t) ?: s.activeGroupOf(t)).shuffle
    }

    /** Padding in dp; applied as a dp margin so the launcher converts it with its own screen density. */
    private fun pad(c: Context, style: WidgetStyle): Float = (Library.get(c).widgetPadding[style] ?: style.defaultPadding).toFloat()

    private fun RemoteViews.padContent(dp: Float) {
        for (side in listOf(RemoteViews.MARGIN_LEFT, RemoteViews.MARGIN_TOP, RemoteViews.MARGIN_RIGHT, RemoteViews.MARGIN_BOTTOM)) {
            setViewLayoutMargin(R.id.content, side, dp, TypedValue.COMPLEX_UNIT_DIP)
        }
    }

    /** Slider widgets: show the next image at the end of the track (on unless turned off in the widget's settings). */
    fun showsPreview(c: Context, id: Int) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("p_$id", true)

    fun setShowsPreview(c: Context, id: Int, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("p_$id", on).apply()
    }

    fun isSlider(c: Context, id: Int) = AppWidgetManager.getInstance(c).getAppWidgetInfo(id)?.provider?.className
        ?.let { name -> providers.entries.any { it.key.name == name && it.value.first == Kind.SLIDE } } == true

    /** A small round thumbnail of [wp] for the track end (kept small: one copy goes into each size variant). */
    private fun roundThumb(wp: Wallpaper): Bitmap? {
        val src = Images.preview(wp.file, wp.crop, 128) ?: return null
        val size = 96
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val scale = maxOf(size.toFloat() / src.width, size.toFloat() / src.height)
        val w = src.width * scale
        val h = src.height * scale
        canvas.drawBitmap(src, null, RectF((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2), paint)
        return out
    }

    private fun broadcast(c: Context, cls: Class<*>, action: String, id: Int, mutable: Boolean) = PendingIntent.getBroadcast(
        c, id, Intent(c, cls).setAction(action).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE,
    )

    fun build(c: Context, id: Int, kind: Kind, style: WidgetStyle, cls: Class<*>): RemoteViews {
        val t = target(c, id)
        val shuffle = shuffling(c, t)
        val p = pad(c, style)
        val m = style == WidgetStyle.MATERIAL
        return when (kind) {
            Kind.NEXT -> RemoteViews(c.packageName, if (m) R.layout.widget_next_m else R.layout.widget_next_o).apply {
                padContent(p)
                applyLook(c, id, style, slider = false)
                setImageViewResource(R.id.icon, if (shuffle) R.drawable.widget_dice_5 else R.drawable.widget_next)
                setContentDescription(android.R.id.background, if (shuffle) "Roll for a random wallpaper on the ${t.phrase}" else "Next wallpaper on the ${t.phrase}")
                setOnClickPendingIntent(android.R.id.background, broadcast(c, cls, ACTION_NEXT, id, mutable = false))
            }
            Kind.SLIDE -> {
                val preview = if (showsPreview(c, id)) Engine.upcoming(c, t)?.let { roundThumb(it.on(t)) } else null
                // One layout per size the launcher reports for this widget, so the track leaves the same
                // gap on every side. One UI shrinks main-screen widgets to fit the cover screen, so the
                // proportions must already be right at the reported size.
                val sizes = reportedSizes(c, id).associateWith { size -> slideFor(c, id, cls, style, size, shuffle, p, t, preview) }
                RemoteViews(sizes)
            }
        }
    }

    private fun slideView(
        c: Context, id: Int, cls: Class<*>, style: WidgetStyle, orient: String, len: Int, size: String, shuffle: Boolean, p: Float, t: Target, preview: Bitmap?,
        chevrons: Boolean,
    ): RemoteViews {
        val s = if (style == WidgetStyle.MATERIAL) "m" else "o"
        val layout = c.resources.getIdentifier("widget_slide_${s}_$orient$len$size", "layout", c.packageName)
        val thumb = c.resources.getIdentifier("widget_${s}_thumb_${if (shuffle) "dice" else "next"}$size", "drawable", c.packageName)
        return RemoteViews(c.packageName, layout).apply {
            padContent(p)
            applyLook(c, id, style, slider = true)
            // Chevrons ("slide this way") replace the label when it's off or wouldn't fit.
            if (chevrons) {
                setViewVisibility(R.id.hint, View.GONE)
                setViewVisibility(R.id.chevrons, View.VISIBLE)
            }
            if (preview != null) {
                setImageViewBitmap(R.id.preview, preview)
                setViewVisibility(R.id.preview, View.VISIBLE)
            } else {
                // No preview: the label centres between the knob and the end of the track.
                setViewLayoutMargin(R.id.hint, RemoteViews.MARGIN_END, 14f, TypedValue.COMPLEX_UNIT_DIP)
                setViewLayoutMargin(R.id.chevrons, RemoteViews.MARGIN_END, 14f, TypedValue.COMPLEX_UNIT_DIP)
            }
            setCompoundButtonChecked(R.id.slider, false)
            setIcon(R.id.slider, "setThumbIcon", Icon.createWithResource(c, thumb))
            setTextViewText(R.id.hint, (if (shuffle) "Shuffle " else "Next on ") + slotName(c, id, t))
            setOnCheckedChangeResponse(R.id.slider, RemoteViews.RemoteResponse.fromPendingIntent(broadcast(c, cls, ACTION_SLIDE, id, mutable = true)))
        }
    }

    /** Advances the widget's slot; with a shuffling group the die rolls in the widget first. */
    fun onNext(c: Context, id: Int, cls: Class<*>, style: WidgetStyle, done: () -> Unit) {
        val t = target(c, id)
        Log.i(Engine.TAG, "widget $id: next on ${t.key}")
        val awm = AppWidgetManager.getInstance(c)
        val layout = if (style == WidgetStyle.MATERIAL) R.layout.widget_next_m else R.layout.widget_next_o
        thread(name = "deskmat-widget") {
            try {
                if (shuffling(c, t)) {
                    val frames = (0 until 8).map { c.resources.getIdentifier("widget_dice_roll_$it", "drawable", c.packageName) }
                    // Fast at first, slowing down as the die settles.
                    for ((i, f) in (frames + frames.take(4)).withIndex()) {
                        awm.partiallyUpdateAppWidget(id, RemoteViews(c.packageName, layout).apply { setImageViewResource(R.id.icon, f) })
                        SystemClock.sleep(45L + i * 12L)
                    }
                    val face = (1..6).random()
                    awm.partiallyUpdateAppWidget(id, RemoteViews(c.packageName, layout).apply {
                        setImageViewResource(R.id.icon, c.resources.getIdentifier("widget_dice_$face", "drawable", c.packageName))
                    })
                    SystemClock.sleep(350)
                }
                targets(c, id).forEach { Commands.execute(c, "next", null, it, waitMs = 0) }
            } finally {
                done()
            }
        }
    }

    fun onSlide(c: Context, id: Int, cls: Class<*>, style: WidgetStyle, checked: Boolean, done: () -> Unit) {
        if (!checked) return done()
        Log.i(Engine.TAG, "widget $id: slide on ${target(c, id).key}")
        thread(name = "deskmat-widget") {
            try {
                targets(c, id).forEach { Commands.execute(c, "next", null, it, waitMs = 0) }
                // Let the thumb sit at the end briefly, then spring back.
                SystemClock.sleep(400)
                AppWidgetManager.getInstance(c).updateAppWidget(id, build(c, id, Kind.SLIDE, style, cls))
            } finally {
                done()
            }
        }
    }
}

/** Shared provider: [kind] and [style] pick the layout; clicks arrive as broadcasts to this class. */
abstract class DeskmatWidget(private val kind: Widgets.Kind, private val style: WidgetStyle) : AppWidgetProvider() {
    override fun onUpdate(c: Context, awm: AppWidgetManager, ids: IntArray) {
        ids.forEach { awm.updateAppWidget(it, Widgets.build(c, it, kind, style, javaClass)) }
    }

    override fun onAppWidgetOptionsChanged(c: Context, awm: AppWidgetManager, id: Int, options: Bundle) {
        awm.updateAppWidget(id, Widgets.build(c, id, kind, style, javaClass))
    }

    override fun onDeleted(c: Context, ids: IntArray) = Widgets.forget(c, ids)

    override fun onReceive(c: Context, intent: Intent) {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        when (intent.action) {
            Widgets.ACTION_NEXT -> {
                val pr = goAsync()
                Widgets.onNext(c.applicationContext, id, javaClass, style) { pr.finish() }
            }
            Widgets.ACTION_SLIDE -> {
                val pr = goAsync()
                Widgets.onSlide(c.applicationContext, id, javaClass, style, intent.getBooleanExtra(RemoteViews.EXTRA_CHECKED, false)) { pr.finish() }
            }
            else -> super.onReceive(c, intent)
        }
    }
}

class NextMaterialWidget : DeskmatWidget(Widgets.Kind.NEXT, WidgetStyle.MATERIAL)
class NextOneUiWidget : DeskmatWidget(Widgets.Kind.NEXT, WidgetStyle.ONEUI)
class SlideMaterialWidget : DeskmatWidget(Widgets.Kind.SLIDE, WidgetStyle.MATERIAL)
class SlideOneUiWidget : DeskmatWidget(Widgets.Kind.SLIDE, WidgetStyle.ONEUI)
