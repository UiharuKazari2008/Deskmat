package dev.dexwp.backdrop.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.util.Log
import dev.dexwp.backdrop.R
import dev.dexwp.backdrop.data.Library
import dev.dexwp.backdrop.engine.Engine
import dev.dexwp.backdrop.engine.Images
import dev.dexwp.backdrop.engine.Rules
import dev.dexwp.backdrop.engine.Screens
import dev.dexwp.backdrop.model.AutoRules
import dev.dexwp.backdrop.model.Screen
import dev.dexwp.backdrop.model.Target
import dev.dexwp.backdrop.ui.MainActivity

/**
 * "Next image when the screen turns off / the phone is folded or unfolded". Android only
 * delivers these to running apps, so a small foreground service holds the receiver while any
 * slot with Automatic on uses one of those rules. Its notification has its own category ("Screen watcher") that can be turned off in
 * Deskmat's notification settings; the service keeps running without it.
 */
object ScreenWatch {
    const val CHANNEL = "screen_watcher"
    private const val NOTIFICATION_ID = 7

    /** Starts or stops the service to match the library. Starting only works from the foreground or boot. */
    fun sync(c: Context) {
        val app = c.applicationContext
        val intent = Intent(app, ScreenWatchService::class.java)
        if (Library.get(app).needsScreenWatch()) {
            runCatching { app.startForegroundService(intent) }
                .onFailure { Log.w(Engine.TAG, "screen watcher not started: $it") }
        } else {
            app.stopService(intent)
        }
    }

    internal fun notification(c: Context): Notification {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Screen watcher", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Deskmat changes the wallpaper each time the screen turns off. Turn this category off to hide it; the wallpaper still changes."
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("New wallpaper at each screen-off")
            .setContentText("Long-press to hide this notification")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    internal fun start(s: Service) {
        s.startForeground(NOTIFICATION_ID, notification(s), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    /** Marks every slot whose active group advances on screen-off, then runs the engine. */
    internal fun onScreenOff(c: Context) = advance(c, "screen-off") { it.onScreenOff }

    /**
     * Folded or unfolded. Slots whose rules ask for this change are marked; each marked slot
     * changes once its screen is out of sight (main screen while folded, cover while unfolded),
     * so the switch is never seen: "on unfold" changes the main screen at the next fold, ready
     * for the next unfold. A slot that changed some other way meanwhile (interval, Next) is dropped.
     */
    internal fun onFoldChanged(c: Context, folded: Boolean) {
        val s = Library.get(c)
        val now = System.currentTimeMillis()
        val asked = s.autoOn.filter { t ->
            !t.isDex && s.rulesFor(s.pinnedGroup(t) ?: s.activeGroupOf(t)).let { if (folded) it.onFold else it.onUnfold }
        }
        val pending = s.pendingHidden + asked.associateWith { now }
        fun hidden(t: Target) = if (t.screen == Screen.MAIN) folded else !folded
        val due = pending.filter { (t, since) -> hidden(t) && (s.changedAt[t] ?: 0L) <= since }.keys
        val keep = pending.filter { (t, since) -> !hidden(t) && (s.changedAt[t] ?: 0L) <= since }
        Log.i(Engine.TAG, "${if (folded) "fold" else "unfold"}: now ${due.joinToString { it.key }.ifEmpty { "-" }}, waiting ${keep.keys.joinToString { it.key }.ifEmpty { "-" }}")
        Library.update(c) { it.copy(pendingHidden = keep, advanceDue = it.advanceDue + due) }
        // The service is in the foreground, so run now: a job can be deferred for minutes.
        if (due.isNotEmpty()) Engine.runAsync(c, if (folded) "fold" else "unfold")
    }

    private fun advance(c: Context, reason: String, wants: (AutoRules) -> Boolean) {
        val s = Library.get(c)
        val due = s.autoOn.filter { wants(s.rulesFor(s.pinnedGroup(it) ?: s.activeGroupOf(it))) }.toSet()
        if (due.isEmpty()) return
        Log.i(Engine.TAG, "$reason: advancing ${due.joinToString { it.key }}")
        Library.update(c) { it.copy(advanceDue = it.advanceDue + due) }
        Engine.runAsync(c, reason)
    }
}

class ScreenWatchService : Service() {
    private var dark = Rules.isDark()
    /** Read in onCreate: a service has no context while it is being constructed. */
    private var folded = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> ScreenWatch.onScreenOff(c.applicationContext)
                Intent.ACTION_CONFIGURATION_CHANGED -> {
                    // Scheduled dark mode (sunset, custom times) flips without changing any setting.
                    if (Rules.isDark() != dark) {
                        dark = Rules.isDark()
                        Engine.runAsync(c.applicationContext, if (dark) "dark mode" else "light mode")
                    }
                    // Folding moves the phone's display onto the other panel.
                    val f = Screens.isFolded(c)
                    if (f != folded) {
                        folded = f
                        ScreenWatch.onFoldChanged(c.applicationContext, f)
                    }
                }
            }
        }
    }
    private var registered = false

    override fun onCreate() {
        super.onCreate()
        folded = Screens.isFolded(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ScreenWatch.start(this)
        if (!Library.get(this).needsScreenWatch()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!registered) {
            registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_CONFIGURATION_CHANGED) })
            registered = true
        }
        return START_STICKY
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Images.trim()
    }

    override fun onDestroy() {
        if (registered) unregisterReceiver(receiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
