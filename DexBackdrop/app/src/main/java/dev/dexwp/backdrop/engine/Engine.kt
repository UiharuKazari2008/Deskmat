package dev.dexwp.backdrop.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.dexwp.backdrop.data.Library
import dev.dexwp.backdrop.data.LibraryState
import dev.dexwp.backdrop.data.SlotStatus
import dev.dexwp.backdrop.model.Group
import dev.dexwp.backdrop.model.Target
import dev.dexwp.backdrop.model.Wallpaper
import dev.dexwp.backdrop.system.DexMonitor
import dev.dexwp.backdrop.system.Scheduler
import java.util.concurrent.Executors

/**
 * Decides what each slot should show and applies it.
 * Priority: manual pin > the first group whose triggers match > the unassigned group > leave as is.
 * A pin lasts until the active group changes (a trigger starts or ends), then triggers take over.
 * Inside the active group the image stays until the group advances (Automatic rules or Next):
 * to the next one in list order, or a random other one with the group's shuffle on.
 */
object Engine {
    const val TAG = "DexBackdrop"
    private val exec = Executors.newSingleThreadExecutor()

    /** [group] is the active group; [wallpaper] is set only for a pin. */
    data class Choice(val wallpaper: Wallpaper?, val reason: String, val group: Group? = null)

    /**
     * Hard floors between two changes of a slot, whatever the settings say: automatic advances
     * (fold, screen off, DeX, interval, broadcasts of events) wait 30 s, hand-made ones 2 s.
     * Protects against floods of events or commands flipping through images.
     */
    const val AUTO_FLOOR_MS = 30_000L
    const val MANUAL_FLOOR_MS = 2_000L

    /** A run waiting in the queue; later requests join it instead of queueing another run. */
    private class Pending(val reasons: MutableSet<String>, val force: MutableSet<Target>, val done: MutableList<() -> Unit>)

    private val queueLock = Any()
    private var pending: Pending? = null

    /**
     * Queues a run. Requests that arrive while one is still waiting are merged into it (one run
     * serves them all), so a burst of events costs one run, not one each.
     */
    fun runAsync(c: Context, reason: String, force: Boolean = false, done: (() -> Unit)? = null) =
        runAsync(c, reason, if (force) Target.entries.toSet() else emptySet(), done)

    /** Summary of the last finished run (one line per slot). */
    @Volatile var lastSummary = ""
        private set

    fun runAsync(c: Context, reason: String, forced: Set<Target>, done: (() -> Unit)? = null) {
        val app = c.applicationContext
        synchronized(queueLock) {
            pending?.let { p ->
                p.reasons += reason
                p.force += forced
                done?.let(p.done::add)
                return
            }
            val p = Pending(mutableSetOf(reason), forced.toMutableSet(), listOfNotNull(done).toMutableList())
            pending = p
            exec.execute {
                synchronized(queueLock) { pending = null }
                run(app, p.reasons.joinToString("+"), p.force)
                p.done.forEach { it() }
            }
        }
    }

    /**
     * Blocking; call off the main thread. Returns a one-line summary per slot.
     * [force] re-applies those slots even when the choice is unchanged.
     * DeX slots written while DeX is off are re-applied on the first run with DeX active.
     */
    @Synchronized
    fun run(c: Context, reason: String, force: Set<Target> = emptySet()): String {
        val lines = mutableListOf<String>()
        try {
            // Only a manual re-check may request a fresh fix; everything else uses cached/passive fixes.
            val loc = Locator.current(c, allowFresh = reason == "manual")
            val now = System.currentTimeMillis()
            DexMonitor.observe(c, now)
            val dexOn = DexMonitor.isDexActive(c)
            val screens = Screens.available(c)
            for (target in Target.entries) {
                // Slots this phone doesn't have (DeX, cover screen) are never written.
                if (target.screen !in screens) continue
                val rules = Rules(now, loc, s0(c).flags, if (s0(c).geofencing) s0(c).inside else null)
                releasePinIfTriggersChanged(c, target, rules)
                val s = Library.get(c)
                val ch = choose(s, target, rules)
                val wp = ch.wallpaper ?: ch.group?.let { pick(c, target, it) }
                // A pin holds the image; automatic advances that come due meanwhile are dropped.
                if (ch.wallpaper != null && target in s.advanceDue) Library.update(c) { it.copy(advanceDue = it.advanceDue - target, advanceManual = it.advanceManual - target) }
                ch.group?.let { g -> if (s.activeGroup[target] != g.id) Library.update(c) { it.copy(activeGroup = it.activeGroup + (target to g.id)) } }
                var why = ch.reason
                if (wp != null) {
                    val key = Applier.key(wp, target, c)
                    val resolve = target.isDex && dexOn && target in s.dexPending
                    if (target in force || resolve || key != s.lastAppliedKey[target]) {
                        // DeX slots with DeX off block ~30 s here (the system waits for a draw that never comes).
                        val t0 = SystemClock.elapsedRealtime()
                        Applier.apply(c, wp, target)
                        Log.i(TAG, "${target.key}: applied in ${SystemClock.elapsedRealtime() - t0} ms, DeX ${if (dexOn) "on" else "off"}")
                        Library.update(c) {
                            it.copy(
                                lastAppliedKey = it.lastAppliedKey + (target to key),
                                dexPending = if (dexOn || !target.isDex) it.dexPending - target else it.dexPending + target,
                                changedAt = if (key.substringBefore(':') != it.lastAppliedKey[target]?.substringBefore(':')) it.changedAt + (target to now) else it.changedAt,
                            )
                        }
                        if (resolve) why += " (re-applied for DeX)"
                    } else {
                        why += " (unchanged)"
                    }
                }
                // Only when what's shown or why changed: a write per slot per run adds up.
                val old = Library.get(c).status[target]
                if (old?.wallpaperId != wp?.id || old?.reason != why) {
                    Library.update(c) { it.copy(status = it.status + (target to SlotStatus(wp?.id, why, now))) }
                }
                lines += "${target.key}: ${wp?.name ?: "-"} — $why"
            }
            val s = Library.get(c)
            Scheduler.scheduleAt(c, minOf(Rules.nextChange(now, loc, s.groups), DexMonitor.nextAdvance(s) ?: Long.MAX_VALUE))
        } catch (t: Throwable) {
            Log.e(TAG, "run failed", t)
            lines += "error: $t"
        }
        return lines.joinToString("\n").also { lastSummary = it; Log.i(TAG, "[$reason] $it") }
    }

    private fun s0(c: Context) = Library.get(c)

    /** The active group without the pin; used to notice trigger changes. */
    private fun autoKey(s: LibraryState, target: Target, rules: Rules): String {
        val ch = choose(s.copy(pins = s.pins - target), target, rules)
        return ch.group?.id ?: ch.reason
    }

    private fun releasePinIfTriggersChanged(c: Context, target: Target, rules: Rules) {
        val s = Library.get(c)
        val pin = s.pins[target] ?: return
        val auto = autoKey(s, target, rules)
        val (basePin, baseAuto) = s.pinBase[target]?.split("|", limit = 2)?.let { it[0] to it.getOrElse(1) { "" } } ?: ("" to "")
        when {
            basePin != pin -> Library.update(c) { it.copy(pinBase = it.pinBase + (target to "$pin|$auto")) }
            baseAuto != auto -> {
                Log.i(TAG, "${target.key}: triggers changed, releasing pin")
                Library.update(c) { it.copy(pins = it.pins - target, pinBase = it.pinBase - target) }
            }
        }
    }

    /**
     * The group whose triggers match first (and that has images Automatic may use), else the
     * unassigned group if it has any.
     */
    fun activeGroup(s: LibraryState, target: Target, rules: Rules): Pair<Group, String>? {
        for (g in s.groupsOn(target)) {
            val match = rules.match(g) ?: continue
            if (s.autoPool(g, target).isNotEmpty()) return g to "Trigger: " + Rules.describe(match)
        }
        val u = s.unassigned(target)
        return if (s.autoPool(u).isNotEmpty()) u to "No trigger matches" else null
    }

    fun choose(s: LibraryState, target: Target, rules: Rules): Choice {
        val active = activeGroup(s, target, rules)
        s.pinnedGroup(target)?.takeIf { s.autoPool(it, target).isNotEmpty() }?.let { g ->
            return Choice(null, "${g.name} · pinned until a trigger changes · ${if (g.shuffle) "shuffle" else "in order"}", g)
        }
        s.byId(s.pins[target])?.takeIf { s.canShow(it, target) }?.let { return Choice(it, "Pinned until a trigger changes", active?.first) }
        val (g, why) = active ?: return Choice(null, "No images in this slot")
        val order = if (g.shuffle) "shuffle" else "in order"
        return Choice(null, "${g.name} · $why · $order", g)
    }

    /**
     * The image a slot's active group shows on its next advance. In order it is simply the next
     * one; a shuffling group draws it now and keeps it queued, so a preview matches what comes.
     */
    fun upcoming(c: Context, target: Target): Wallpaper? {
        val s = Library.get(c)
        val g = s.pinnedGroup(target) ?: s.activeGroupOf(target)
        val pool = s.autoPool(g, target).ifEmpty { return null }
        val key = s.pickKey(g, target)
        val current = pool.firstOrNull { it.id == s.picks[key] }
        if (!g.shuffle) return if (current == null) pool.first() else pool[(pool.indexOf(current) + 1) % pool.size]
        pool.firstOrNull { it.id == s.queued[key] && it != current }?.let { return it }
        val drawn = pool.filter { it != current }.ifEmpty { pool }.random()
        Library.update(c) { it.copy(queued = it.queued + (key to drawn.id)) }
        return drawn
    }

    /**
     * The group's current image while it is still in the group (and not skipped) and the slot is
     * not due to advance; otherwise the next one in order, or a random other one with shuffle on.
     * Skipped images are never chosen.
     */
    private fun pick(c: Context, target: Target, g: Group): Wallpaper? {
        val s = Library.get(c)
        val pool = s.autoPool(g, target).ifEmpty { return null }
        // A global group keeps a current image per slot; a linked group one for all its slots.
        val key = s.pickKey(g, target)
        val current = pool.firstOrNull { it.id == s.picks[key] }
        // A linked group advances once for all the slots it is showing on, so they stay in step.
        val slots = s.syncSlots(g, target).filter { it == target || s.activeGroup[it] == g.id }.toSet()
        val due = slots.filter { it in s.advanceDue }
        if (current != null && due.isEmpty()) return current
        val manual = due.any { it in s.advanceManual }
        val since = slots.mapNotNull { s.changedAt[it] }.maxOrNull() ?: 0L
        val shownMs = System.currentTimeMillis() - since
        if (current != null && shownMs < if (manual) MANUAL_FLOOR_MS else AUTO_FLOOR_MS) {
            Library.update(c) { it.copy(advanceDue = it.advanceDue - slots, advanceManual = it.advanceManual - slots) }
            Log.i(TAG, "${target.key}: advance ignored, changed ${shownMs / 1000} s ago")
            return current
        }
        // Automatic advances respect the group's minimum time and "must have been seen"; Next by hand doesn't.
        if (current != null && !manual) {
            val rules = s.rulesFor(g)
            val tooSoon = rules.minShowMin > 0 && shownMs < rules.minShowMin * 60_000L
            val unseen = rules.mustBeSeen && due.none { Seen.since(c, it, since) }
            if (tooSoon || unseen) {
                Library.update(c) { it.copy(advanceDue = it.advanceDue - slots) }
                Log.i(TAG, "${target.key}: advance skipped, ${if (tooSoon) "shown under ${rules.minShowMin} min" else "not seen yet"}")
                return current
            }
        }
        val next = when {
            g.shuffle -> pool.firstOrNull { it.id == s.queued[key] && it != current } ?: pool.filter { it != current }.ifEmpty { pool }.random()
            current == null -> pool.first()
            else -> pool[(pool.indexOf(current) + 1) % pool.size]
        }
        Library.update(c) {
            it.copy(picks = it.picks + (key to next.id), queued = it.queued - key, advanceDue = it.advanceDue - slots, advanceManual = it.advanceManual - slots)
        }
        Log.i(TAG, "${target.key}: ${g.name} → ${next.name} (${pool.size} images, ${if (g.shuffle) "shuffle" else "in order"})")
        return next
    }
}
