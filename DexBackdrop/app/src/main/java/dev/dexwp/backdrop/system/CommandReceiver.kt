package dev.dexwp.backdrop.system

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import dev.dexwp.backdrop.engine.Commands
import dev.dexwp.backdrop.model.Target
import kotlin.concurrent.thread

/**
 * System events, the schedule tick, and commands:
 *
 *   am broadcast -n dev.dexwp.backdrop/.system.CommandReceiver -a dev.dexwp.backdrop.COMMAND \
 *       --es cmd set --es arg work [--es target home|lock] [--es state on|off|toggle]
 *
 * The reply text comes back as the broadcast result data (printed by `am broadcast`).
 */
class CommandReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        Scheduler.ensureJob(c)
        when (intent.action) {
            ACTION_COMMAND -> {
                // Only ordered broadcasts (`am broadcast`) carry a reply; read before goAsync() clears it.
                val ordered = isOrderedBroadcast
                val pr = goAsync()
                thread {
                    try {
                        val reply = run(c, intent.extras)
                        if (ordered) {
                            pr.resultCode = if (reply.startsWith("error")) Activity.RESULT_CANCELED else Activity.RESULT_OK
                            pr.resultData = reply
                        }
                    } finally {
                        pr.finish()
                    }
                }
            }
            Scheduler.ACTION_TICK -> ApplyJob.request(c, "tick")
            else -> {
                // Geofences are dropped on reboot, app update and Play services data clear.
                Geofences.sync(c)
                // Boot and app updates are allowed to start the screen watcher in the background.
                ScreenWatch.sync(c)
                ApplyJob.request(c, intent.action?.substringAfterLast('.')?.lowercase() ?: "event")
            }
        }
    }

    companion object {
        const val ACTION_COMMAND = "dev.dexwp.backdrop.COMMAND"

        fun run(c: Context, extras: Bundle?): String {
            val cmd = extras?.getString("cmd") ?: return "error: missing --es cmd"
            return Commands.execute(c, cmd, extras.getString("arg"), Target.of(extras.getString("target")), extras.getString("state"))
        }

        fun intent(c: Context, cmd: String, arg: String? = null, target: Target? = null, state: String? = null) =
            Intent(ACTION_COMMAND).setClass(c, ShortcutActivity::class.java)
                .putExtra("cmd", cmd).putExtra("arg", arg).putExtra("target", target?.key).putExtra("state", state)
    }
}

/** Invisible target for app shortcuts (what Modes and Routines' "app action" runs). */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extras = intent?.extras
        if (intent?.action == CommandReceiver.ACTION_COMMAND && extras != null) {
            sendBroadcast(Intent(CommandReceiver.ACTION_COMMAND).setClass(this, CommandReceiver::class.java).putExtras(extras))
            val label = listOfNotNull(extras.getString("cmd"), extras.getString("arg")).joinToString(" ")
            Toast.makeText(this, "Deskmat: $label", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
