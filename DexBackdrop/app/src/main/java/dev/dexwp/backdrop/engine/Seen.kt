package dev.dexwp.backdrop.engine

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import dev.dexwp.backdrop.model.Target

/**
 * "Must have been seen": whether a slot's image can have been looked at since it changed. Home
 * slots count as seen once a launcher (phone or DeX home) came to the front, or is in front now;
 * lock slots once the lock screen was shown. Needs Usage access; without it everything counts as
 * seen, so the rule never blocks.
 */
object Seen {
    private const val LOOKBACK_MS = 6 * 60 * 60_000L

    fun hasAccess(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
    }

    private fun launchers(c: Context): Set<String> =
        c.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet() + "com.sec.android.app.launcher"

    fun since(c: Context, t: Target, since: Long): Boolean {
        if (since <= 0L || !hasAccess(c)) return true
        val now = System.currentTimeMillis()
        val events = c.getSystemService(UsageStatsManager::class.java).queryEvents(since - LOOKBACK_MS, now)
        val home = launchers(c)
        val e = UsageEvents.Event()
        var homeInFront = false
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    homeInFront = e.packageName in home
                    if (!t.lock && homeInFront && e.timeStamp >= since) return true
                }
                UsageEvents.Event.KEYGUARD_SHOWN -> if (t.lock && e.timeStamp >= since) return true
            }
        }
        // Changed while the home screen was already in front: it is on screen now.
        return !t.lock && homeInFront
    }
}
