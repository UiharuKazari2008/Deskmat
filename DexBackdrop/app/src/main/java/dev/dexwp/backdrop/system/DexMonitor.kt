package dev.dexwp.backdrop.system

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.hardware.display.DisplayManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.Display
import dev.dexwp.backdrop.data.Library
import dev.dexwp.backdrop.data.LibraryState
import dev.dexwp.backdrop.engine.Engine
import dev.dexwp.backdrop.engine.Screens
import dev.dexwp.backdrop.model.Target

/**
 * Tracks DeX connect/disconnect for Automatic. DeX shows up as an extra (non-private) display.
 * A content-trigger job on DeX-related system settings wakes the app when DeX starts or
 * stops, even while it is closed; every engine run also re-checks.
 */
object DexMonitor {
    private const val JOB_ID = 2

    /** Settings that One UI updates when a DeX session starts or ends, plus the dark mode setting. */
    private val WATCHED: List<Uri> = listOf(
        Settings.System.getUriFor("dex_on_external_display"),
        Settings.Global.getUriFor("dex_touchpad_desktop_display_id"),
        Settings.Global.getUriFor("flow_pointer_is_on_dex"),
        Settings.Global.getUriFor("keyboard_dex"),
        Settings.System.getUriFor("dex_need_dark_statusbar"),
        // Dark mode switched from quick settings or Settings (triggers on light/dark mode).
        Settings.Secure.getUriFor("ui_night_mode"),
    )

    /** Phones without DeX can still have external displays (Smart View, casting); those don't count. */
    fun isDexActive(c: Context): Boolean = Screens.dexSupported(c) &&
        c.getSystemService(DisplayManager::class.java).displays.any {
            it.displayId != Display.DEFAULT_DISPLAY && it.flags and Display.FLAG_PRIVATE == 0
        }

    /**
     * Records DeX state changes and marks slots whose active group should advance: on DeX
     * start/end and when the interval has run out, per the active group's Automatic rules.
     */
    fun observe(c: Context, now: Long) {
        val s = Library.get(c)
        val active = isDexActive(c)
        val due = mutableSetOf<Target>()
        for (t in s.autoOn) {
            val r = s.rulesFor(s.activeGroupOf(t))
            if (s.dexActive && !active && r.onDexEnd) due += t
            if (!s.dexActive && active && r.onDexStart) due += t
            if (r.interval && (active || !t.isDex) && now - (s.changedAt[t] ?: 0L) >= r.intervalMin * 60_000L) due += t
        }
        if (active != s.dexActive) Log.i(Engine.TAG, "DeX ${if (active) "connected" else "disconnected"}")
        if (active != s.dexActive || (due - s.advanceDue).isNotEmpty()) {
            Library.update(c) { it.copy(dexActive = active, advanceDue = it.advanceDue + due) }
        }
    }

    /** When the next interval runs out (null if none is running). */
    fun nextAdvance(s: LibraryState): Long? =
        s.autoOn.filter { it !in s.advanceDue && (s.dexActive || !it.isDex) }.mapNotNull { t ->
            val r = s.rulesFor(s.activeGroupOf(t))
            if (r.interval) (s.changedAt[t] ?: 0L) + r.intervalMin * 60_000L else null
        }.minOrNull()?.coerceAtLeast(System.currentTimeMillis() + 60_000L)

    /** (Re)arms the one-shot content-trigger job. */
    fun watch(c: Context) {
        val b = JobInfo.Builder(JOB_ID, ComponentName(c, DexWatchJobService::class.java))
            .setTriggerContentUpdateDelay(500)
            .setTriggerContentMaxDelay(3_000)
        WATCHED.forEach { b.addTriggerContentUri(JobInfo.TriggerContentUri(it, 0)) }
        c.getSystemService(JobScheduler::class.java).schedule(b.build())
    }
}

class DexWatchJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Log.i(Engine.TAG, "DeX setting changed: ${params.triggeredContentUris?.joinToString()}")
        Engine.runAsync(this, "dex") {
            DexMonitor.watch(this)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        DexMonitor.watch(this)
        return false
    }
}
