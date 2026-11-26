package dev.dexwp.backdrop.ui

import android.graphics.Bitmap
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.materialkolor.dynamicColorScheme
import dev.dexwp.backdrop.engine.Images
import dev.dexwp.backdrop.model.Crop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Material You from the system tonal palettes. One UI overrides system_accent1/2/3 and
 * system_neutral1/2 with the user's colour palette but leaves the Android 14 role tokens
 * (system_primary_dark, ...) at stock values, which Compose's dynamic schemes read on 34+.
 * So the roles are mapped from the palettes here (Material 3 tonal-spot tones).
 */
@Composable
fun BackdropTheme(seed: Color? = null, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    // With a seed (the current image), build a Material 3 tonal-spot scheme from it.
    val scheme = remember(dark, seed, ctx.resources.configuration) {
        if (seed != null) dynamicColorScheme(seedColor = seed, isDark = dark, isAmoled = false) else systemScheme(ctx, dark)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private fun systemScheme(ctx: Context, dark: Boolean): ColorScheme {
    // Palette index → tone: 0=100, 10=99, 50=95, 100=90, 200=80 … 900=10, 1000=0.
    fun pal(name: String, step: Int): Color {
        val id = ctx.resources.getIdentifier("system_${name}_$step", "color", "android")
        return Color(ctx.getColor(id))
    }
    fun a1(s: Int) = pal("accent1", s)
    fun a2(s: Int) = pal("accent2", s)
    fun a3(s: Int) = pal("accent3", s)
    fun n1(s: Int) = pal("neutral1", s)
    fun n2(s: Int) = pal("neutral2", s)
    /** Neutral tone between two palette steps (t = 0 → a, 1 → b). */
    fun nt(a: Int, b: Int, t: Float) = lerp(n1(a), n1(b), t)

    return if (dark) darkColorScheme(
        primary = a1(200), onPrimary = a1(800), primaryContainer = a1(700), onPrimaryContainer = a1(100), inversePrimary = a1(600),
        secondary = a2(200), onSecondary = a2(800), secondaryContainer = a2(700), onSecondaryContainer = a2(100),
        tertiary = a3(200), onTertiary = a3(800), tertiaryContainer = a3(700), onTertiaryContainer = a3(100),
        background = nt(900, 1000, 0.4f), onBackground = n1(100),
        surface = nt(900, 1000, 0.4f), onSurface = n1(100), surfaceTint = a1(200),
        surfaceVariant = n2(700), onSurfaceVariant = n2(200),
        surfaceDim = nt(900, 1000, 0.4f), surfaceBright = nt(800, 700, 0.4f),
        surfaceContainerLowest = nt(900, 1000, 0.6f), surfaceContainerLow = n1(900),
        surfaceContainer = nt(900, 800, 0.2f), surfaceContainerHigh = nt(900, 800, 0.7f), surfaceContainerHighest = nt(800, 700, 0.2f),
        inverseSurface = n1(100), inverseOnSurface = n1(800),
        outline = n2(400), outlineVariant = n2(700),
    ) else lightColorScheme(
        primary = a1(600), onPrimary = a1(0), primaryContainer = a1(100), onPrimaryContainer = a1(900), inversePrimary = a1(200),
        secondary = a2(600), onSecondary = a2(0), secondaryContainer = a2(100), onSecondaryContainer = a2(900),
        tertiary = a3(600), onTertiary = a3(0), tertiaryContainer = a3(100), onTertiaryContainer = a3(900),
        background = nt(10, 50, 0.25f), onBackground = n1(900),
        surface = nt(10, 50, 0.25f), onSurface = n1(900), surfaceTint = a1(600),
        surfaceVariant = n2(100), onSurfaceVariant = n2(700),
        surfaceDim = nt(100, 200, 0.3f), surfaceBright = nt(10, 50, 0.25f),
        surfaceContainerLowest = n1(0), surfaceContainerLow = nt(50, 10, 0.25f),
        surfaceContainer = nt(50, 100, 0.2f), surfaceContainerHigh = nt(50, 100, 0.6f), surfaceContainerHighest = n1(100),
        inverseSurface = n1(800), inverseOnSurface = n1(50),
        outline = n2(500), outlineVariant = n2(200),
    )
}

/** Decodes [crop] of the image off the main thread; shows a placeholder until ready. */
@Composable
fun WallpaperImage(path: String, crop: Crop?, maxSide: Int, modifier: Modifier, scale: ContentScale = ContentScale.Crop) {
    val bmp by produceState<Bitmap?>(null, path, crop, maxSide) {
        value = withContext(Dispatchers.IO) { Images.preview(path, crop, maxSide) }
    }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), null, modifier, contentScale = scale)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

@Composable
fun Modifier.clipMedium(): Modifier = this.then(Modifier.clip(MaterialTheme.shapes.medium))

/** Material "push_pin" glyph (not in material-icons-core). */
val PushPinIcon: ImageVector by lazy {
    ImageVector.Builder("PushPin", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes(
            "M16,9V4l1,0c0.55,0 1,-0.45 1,-1v0c0,-0.55 -0.45,-1 -1,-1H7C6.45,2 6,2.45 6,3v0c0,0.55 0.45,1 1,1l1,0v5" +
                "c0,1.66 -1.34,3 -3,3h0v2h5.97v7l1,1l1,-1v-7H19v-2h0C17.34,12 16,10.66 16,9z",
        ),
        fill = SolidColor(Color.Black),
    ).build()
}

/** Material "desktop_windows" glyph. */
val DesktopIcon: ImageVector by lazy {
    ImageVector.Builder("Desktop", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes(
            "M21,2H3C1.9,2 1,2.9 1,4v12c0,1.1 0.9,2 2,2h7v2H8v2h8v-2h-2v-2h7c1.1,0 2,-0.9 2,-2V4C23,2.9 22.1,2 21,2z" +
                "M21,16H3V4h18V16z",
        ),
        fill = SolidColor(Color.Black),
    ).build()
}

/** Material "casino" glyph: a die. */
val DiceIcon: ImageVector by lazy {
    ImageVector.Builder("Dice", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes(
            "M19,3H5C3.9,3 3,3.9 3,5v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5C21,3.9 20.1,3 19,3z" +
                "M7.5,18C6.67,18 6,17.33 6,16.5S6.67,15 7.5,15S9,15.67 9,16.5S8.33,18 7.5,18z" +
                "M7.5,9C6.67,9 6,8.33 6,7.5S6.67,6 7.5,6S9,6.67 9,7.5S8.33,9 7.5,9z" +
                "M12,13.5c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5s1.5,0.67 1.5,1.5S12.83,13.5 12,13.5z" +
                "M16.5,18c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5s1.5,0.67 1.5,1.5S17.33,18 16.5,18z" +
                "M16.5,9C15.67,9 15,8.33 15,7.5S15.67,6 16.5,6S18,6.67 18,7.5S17.33,9 16.5,9z",
        ),
        fill = SolidColor(Color.Black),
    ).build()
}

/** Material "autorenew" glyph. */
val AutoIcon: ImageVector by lazy {
    ImageVector.Builder("Autorenew", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes(
            "M12,6v3l4,-4 -4,-4v3c-4.42,0 -8,3.58 -8,8 0,1.57 0.46,3.03 1.24,4.26L6.7,14.8c-0.45,-0.83 -0.7,-1.79 -0.7,-2.8 0,-3.31 2.69,-6 6,-6z" +
                "M18.76,7.74L17.3,9.2c0.44,0.84 0.7,1.79 0.7,2.8 0,3.31 -2.69,6 -6,6v-3l-4,4 4,4v-3c4.42,0 8,-3.58 8,-8 0,-1.57 -0.46,-3.03 -1.24,-4.26z",
        ),
        fill = SolidColor(Color.Black),
    ).build()
}

/** Material "smartphone" glyph (cover screen, or the only screen of a non-foldable phone). */
val PhoneIcon: ImageVector by lazy {
    ImageVector.Builder("Phone", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes(
            "M17,1.01L7,1c-1.1,0 -2,0.9 -2,2v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V3c0,-1.1 -0.9,-1.99 -2,-1.99z" +
                "M17,19H7V5h10v14z",
        ),
        fill = SolidColor(Color.Black),
    ).build()
}

/** An unfolded phone: two panels side by side (the main screen of a foldable). */
val FoldIcon: ImageVector by lazy {
    ImageVector.Builder("Fold", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes(
            "M20,3H4C2.9,3 2,3.9 2,5v14c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V5C22,3.9 21.1,3 20,3z" +
                "M11,19H4V5h7v14zM20,19h-7V5h7v14z",
        ),
        fill = SolidColor(Color.Black),
    ).build()
}

/** Material "create_new_folder" glyph. */
val NewGroupIcon: ImageVector by lazy {
    ImageVector.Builder("NewGroup", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes(
            "M20,6h-8l-2,-2H4c-1.11,0 -1.99,0.89 -1.99,2L2,18c0,1.11 0.89,2 2,2h16c1.11,0 2,-0.89 2,-2V8c0,-1.11 -0.89,-2 -2,-2z" +
                "M19,14h-3v3h-2v-3h-3v-2h3V9h2v3h3v2z",
        ),
        fill = SolidColor(Color.Black),
    ).build()
}

/** Material "dark_mode" glyph (a crescent moon). */
val DarkModeIcon: ImageVector by lazy {
    ImageVector.Builder("DarkMode", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes(
            "M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9s9,-4.03 9,-9c0,-0.46 -0.04,-0.92 -0.1,-1.36" +
                "c-0.98,1.37 -2.58,2.26 -4.4,2.26c-2.98,0 -5.4,-2.42 -5.4,-5.4c0,-1.81 0.89,-3.42 2.26,-4.4C12.92,3.04 12.46,3 12,3z",
        ),
        fill = SolidColor(Color.Black),
    ).build()
}

/** Material "pause" glyph. */
val PauseIcon: ImageVector by lazy {
    ImageVector.Builder("Pause", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes("M6,19h4V5H6v14zM14,5v14h4V5h-4z"),
        fill = SolidColor(Color.Black),
    ).build()
}

/** Material "fast_forward" glyph: next image. */
val NextIcon: ImageVector by lazy {
    ImageVector.Builder("FastForward", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes("M4,18l8.5,-6L4,6v12zM13,6v12l8.5,-6L13,6z"),
        fill = SolidColor(Color.Black),
    ).build()
}

/** Shown while a slot is pinned: triggers are paused until one changes, or until Unpin. */
@Composable
fun PinnedBanner(slot: String, name: String, onUnpin: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(PushPinIcon, null, Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Automatic triggers for the ${slot} are paused", style = MaterialTheme.typography.titleSmall)
                Text(
                    "$name is pinned. Triggers take over again when one starts or ends, or when you unpin.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onUnpin, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                Text("Unpin")
            }
        }
    }
}

/** Material "open_in_new" glyph. */
val OpenIcon: ImageVector by lazy {
    ImageVector.Builder("OpenIcon", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes("M19,19H5V5h7V3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2v7zM14,3v2h3.59l-9.83,9.83 1.41,1.41L19,6.41V10h2V3h-7z"),
        fill = SolidColor(Color.Black),
    ).build()
}

/** Material "content_copy" glyph. */
val CopyIcon: ImageVector by lazy {
    ImageVector.Builder("CopyIcon", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes("M16,1H4C2.9,1 2,1.9 2,3v14h2V3h12V1zM19,5H8C6.9,5 6,5.9 6,7v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7C21,5.9 20.1,5 19,5zM19,21H8V7h11V21z"),
        fill = SolidColor(Color.Black),
    ).build()
}

/** Material "drive_file_move" glyph. */
val MoveIcon: ImageVector by lazy {
    ImageVector.Builder("MoveIcon", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes("M20,6h-8l-2,-2H4C2.9,4 2.01,4.9 2.01,6L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8C22,6.9 21.1,6 20,6zM12,17v-3H8v-4h4V7l5,5L12,17z"),
        fill = SolidColor(Color.Black),
    ).build()
}

/** Material "flag" glyph. */
val FlagIcon: ImageVector by lazy {
    ImageVector.Builder("FlagIcon", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes("M14.4,6L14,4H5v17h2v-7h5.6l0.4,2h7V6z"),
        fill = SolidColor(Color.Black),
    ).build()
}

/** Material "add_photo_alternate" glyph. */
val ImportIcon: ImageVector by lazy {
    ImageVector.Builder("ImportIcon", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes("M19,7v2.99s-1.99,0.01 -2,0V7h-3s0.01,-1.99 0,-2h3V2h2v3h3v2h-3zM16,11V8h-3V5H5c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2v-8h-3zM5,19l3,-4 2,3 3,-4 4,5H5z"),
        fill = SolidColor(Color.Black),
    ).build()
}

/** Material "block" glyph. */
val SkipIcon: ImageVector by lazy {
    ImageVector.Builder("SkipIcon", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes("M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM4,12c0,-4.42 3.58,-8 8,-8 1.85,0 3.55,0.63 4.9,1.69L5.69,16.9C4.63,15.55 4,13.85 4,12zM12,20c-1.85,0 -3.55,-0.63 -4.9,-1.69L18.31,7.1C19.37,8.45 20,10.15 20,12c0,4.42 -3.58,8 -8,8z"),
        fill = SolidColor(Color.Black),
    ).build()
}

/** Material "remove_circle_outline" glyph. */
val RemoveIcon: ImageVector by lazy {
    ImageVector.Builder("RemoveIcon", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes("M7,11v2h10v-2H7zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8z"),
        fill = SolidColor(Color.Black),
    ).build()
}

/** Material "library_add" glyph. */
val AddToIcon: ImageVector by lazy {
    ImageVector.Builder("AddToIcon", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes("M4,6H2v14c0,1.1 0.9,2 2,2h14v-2H4V6zM20,2H8C6.9,2 6,2.9 6,4v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4C22,2.9 21.1,2 20,2zM19,11h-4v4h-2v-4H9V9h4V5h2v4h4v2z"),
        fill = SolidColor(Color.Black),
    ).build()
}
