package dev.dexwp.backdrop.system

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.job.JobWorkItem
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.dexwp.backdrop.engine.Engine
import dev.dexwp.backdrop.model.Target
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Engine runs requested from broadcasts and Routines go through a job instead of the
 * broadcast's 60 s window: while DeX is off, each DeX wallpaper write blocks ~30 s waiting
 * for a draw that never comes. Requests are queued as work items and coalesced into one run.
 */
object ApplyJob {
    private const val JOB_ID = 3
    private val nextId = AtomicLong(System.nanoTime())
    private val waiting = ConcurrentHashMap<Long, CompletableFuture<String>>()

    /** Queues an engine run; the future completes with its summary. [force] re-applies those slots. */
    fun request(c: Context, reason: String, force: Set<Target> = emptySet()): CompletableFuture<String> {
        val id = nextId.incrementAndGet()
        val result = CompletableFuture<String>()
        waiting[id] = result
        val work = JobWorkItem(
            Intent().putExtra("id", id).putExtra("reason", reason)
                .putExtra("force", force.map { it.key }.toTypedArray()),
        )
        val js = c.getSystemService(JobScheduler::class.java)
        val cn = ComponentName(c, ApplyJobService::class.java)
        val expedited = runCatching {
            js.enqueue(JobInfo.Builder(JOB_ID, cn).setExpedited(true).build(), work) == JobScheduler.RESULT_SUCCESS
        }.onFailure { Log.w(Engine.TAG, "expedited apply job: $it") }.getOrDefault(false)
        if (!expedited) js.enqueue(JobInfo.Builder(JOB_ID, cn).build(), work)
        return result
    }

    internal fun complete(id: Long, reply: String) {
        waiting.remove(id)?.complete(reply)
    }
}

class ApplyJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "deskmat-apply") {
            try {
                while (true) {
                    // A null dequeue after all work is completed ends the job.
                    val batch = generateSequence { params.dequeueWork() }.toList()
                    if (batch.isEmpty()) break
                    val reason = batch.mapNotNull { it.intent.getStringExtra("reason") }.distinct().joinToString("+").ifEmpty { "event" }
                    val force = batch.flatMap { it.intent.getStringArrayExtra("force").orEmpty().mapNotNull(Target::of) }.toSet()
                    val reply = Engine.run(applicationContext, reason, force)
                    batch.forEach {
                        ApplyJob.complete(it.intent.getLongExtra("id", 0), reply)
                        params.completeWork(it)
                    }
                }
            } catch (e: Exception) {
                // dequeueWork/completeWork throw once the job has been stopped; the rest is redelivered.
                Log.w(Engine.TAG, "apply job ended: $e")
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}
