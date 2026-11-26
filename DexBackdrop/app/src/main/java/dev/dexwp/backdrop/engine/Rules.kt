package dev.dexwp.backdrop.engine

import android.content.res.Configuration
import android.content.res.Resources
import android.location.Location
import dev.dexwp.backdrop.model.Condition
import dev.dexwp.backdrop.model.RuleGroup
import dev.dexwp.backdrop.model.Group
import dev.dexwp.backdrop.system.Geofences
import java.util.Calendar
import java.util.Locale

/** Evaluates trigger rules: a group is active when any rule group matches; a rule group when all its conditions hold. */
class Rules(
    private val now: Long,
    private val loc: Location?,
    private val flags: Map<String, Boolean>,
    /** Geofence ids we are inside, or null when geofencing is unavailable (distance fallback). */
    private val inside: Set<String>? = null,
) {
    private val cal = Calendar.getInstance().apply { timeInMillis = now }
    private val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    private val today = isoDay(cal.get(Calendar.DAY_OF_WEEK))
    private val phase by lazy { SunTimes.plan(now, loc).phaseAt(now) }

    /** The first matching rule group, or null. */
    fun match(g: Group): RuleGroup? = g.triggers.firstOrNull { g -> g.conditions.isNotEmpty() && g.conditions.all(::holds) }

    fun holds(c: Condition): Boolean = when (c) {
        is Condition.Time -> {
            val yesterday = if (today == 1) 7 else today - 1
            when {
                c.startMin == c.endMin -> today in c.days
                c.startMin < c.endMin -> minute in c.startMin until c.endMin && today in c.days
                else -> (minute >= c.startMin && today in c.days) || (minute < c.endMin && yesterday in c.days)
            }
        }
        is Condition.Sun -> phase in c.phases
        is Condition.Place -> if (inside != null) Geofences.id(c) in inside else loc != null && FloatArray(1).also {
            Location.distanceBetween(loc.latitude, loc.longitude, c.lat, c.lon, it)
        }[0] <= c.radius + minOf(loc.accuracy, 300f)
        is Condition.Flag -> (flags[c.name] ?: false) == c.on
        is Condition.Theme -> isDark() == c.dark
    }

    companion object {
        /** The phone's current light/dark mode (system resources follow it even while the app is in the background). */
        fun isDark() = Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

        /** Calendar.DAY_OF_WEEK → 1 = Monday … 7 = Sunday. */
        fun isoDay(calDay: Int) = (calDay + 5) % 7 + 1

        val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")

        /** Next time any time-based condition could change. */
        fun nextChange(now: Long, loc: Location?, groups: List<Group>): Long {
            var next = SunTimes.nextBoundary(now, loc)
            val cal = Calendar.getInstance().apply { timeInMillis = now }
            val midnight = (cal.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            groups.flatMap { it.triggers }.flatMap { it.conditions }.filterIsInstance<Condition.Time>().forEach { t ->
                for (m in listOf(t.startMin, t.endMin)) {
                    var at = midnight + m * 60_000L
                    if (at <= now) at += 86_400_000L
                    if (at < next) next = at
                }
            }
            return next
        }

        fun describe(c: Condition): String = when (c) {
            is Condition.Time -> "${hhmm(c.startMin)}–${hhmm(c.endMin)}" +
                if (c.days.size == 7) " every day" else " " + c.days.sorted().joinToString("") { DAY_LETTERS[it - 1] }
            is Condition.Sun -> "Sun: " + SunTimes.PHASES.filter { it in c.phases }.joinToString(", ")
            is Condition.Place -> "At ${c.label} (${c.radius.toInt()} m)"
            is Condition.Flag -> "Flag “${c.name}” is ${if (c.on) "on" else "off"}"
            is Condition.Theme -> if (c.dark) "Dark mode" else "Light mode"
        }

        fun describe(g: RuleGroup) = g.conditions.joinToString(" AND ") { describe(it) }

        fun hhmm(m: Int) = String.format(Locale.US, "%02d:%02d", m / 60, m % 60)
    }
}
