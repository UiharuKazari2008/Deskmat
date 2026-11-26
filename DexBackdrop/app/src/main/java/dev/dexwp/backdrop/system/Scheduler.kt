package dev.dexwp.backdrop.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dev.dexwp.backdrop.engine.Engine

/**
 * An inexact alarm at the next time a trigger can change (no exact-alarm permission needed),
 * plus a 15-minute periodic job for location and DeX-monitor changes.
 */
object Scheduler {
    const val ACTION_TICK = "dev.dexwp.backdrop.TICK"
    private const val JOB_ID = 1

    private fun js(c: Context) = c.getSystemService(JobScheduler::class.java)

    fun scheduleAt(c: Context, at: Long) {
        val pi = PendingIntent.getBroadcast(
            c, 0, Intent(ACTION_TICK).setClass(c, CommandReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        c.getSystemService(AlarmManager::class.java).setWindow(AlarmManager.RTC, at, 2 * 60_000L, pi)
    }

    fun ensureJob(c: Context) {
        // Re-arm every time so a changed list of watched settings (e.g. dark mode) takes effect after updates.
        DexMonitor.watch(c)
        val js = c.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(JOB_ID) != null) return
        js.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(c, UpdateJobService::class.java))
                .setPeriodic(15 * 60_000L).setPersisted(true).build(),
        )
    }
}

class UpdateJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Engine.runAsync(this, "job") { jobFinished(params, false) }
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}
