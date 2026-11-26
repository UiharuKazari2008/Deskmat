package dev.dexwp.backdrop.remote

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.dexwp.backdrop.ui.BackdropTheme

/**
 * Asks the user whether the calling app may read or control Deskmat. Start it with
 * startActivityForResult (that is how Deskmat learns, from Android, which app is asking):
 *
 *   Intent("dev.dexwp.backdrop.REQUEST_ACCESS").setPackage("dev.dexwp.backdrop").putExtra("scope", "control")
 *
 * Result: RESULT_OK with extra "scope" (the scope now held), or RESULT_CANCELED.
 */
class AccessRequestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Other apps' windows can't sit on top of this dialog and trick the user into tapping Allow.
        window.setHideOverlayWindows(true)
        val pkg = callingPackage
        val wanted = Scope.of(intent.getStringExtra("scope")) ?: Scope.READ
        if (pkg == null || pkg == packageName) return finishWith(null)
        val held = RemoteAccess.list(this).firstOrNull { it.pkg == pkg && it.certSha256 == RemoteAccess.certOf(this, pkg) }?.scope
        if (RemoteAccess.allows(held, wanted)) return finishWith(held)

        val label = RemoteAccess.label(this, pkg)
        val icon = runCatching { packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        setContent {
            BackdropTheme {
                AlertDialog(
                    onDismissRequest = { finishWith(null) },
                    icon = icon?.let { { Image(it, null, Modifier.size(48.dp)) } },
                    title = { Text("Let $label use Deskmat?") },
                    text = {
                        Column {
                            if (label != pkg) Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.padding(top = 8.dp))
                            Bullet("See which wallpaper each screen shows, your groups, images and flags")
                            Bullet("Get the image files, full and cropped, and where they came from")
                            if (wanted == Scope.CONTROL) {
                                Bullet("Change wallpapers: next image, set an image or group, Automatic and Shuffle")
                                Bullet("Turn flags on and off")
                            }
                            Spacer(Modifier.padding(top = 8.dp))
                            Text("You can take this back in Deskmat → Settings → Connected apps.", style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { finishWith(if (RemoteAccess.grant(this, pkg, wanted)) wanted else null) }) { Text("Allow") }
                    },
                    dismissButton = { TextButton(onClick = { finishWith(null) }) { Text("Don't allow") } },
                )
            }
        }
    }

    private fun finishWith(scope: Scope?) {
        if (scope == null) setResult(Activity.RESULT_CANCELED)
        else setResult(Activity.RESULT_OK, Intent().putExtra("scope", scope.key))
        finish()
    }
}

@androidx.compose.runtime.Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
        Text("•")
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
