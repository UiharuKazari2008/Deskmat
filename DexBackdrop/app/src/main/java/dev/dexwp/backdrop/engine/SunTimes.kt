package dev.dexwp.backdrop.engine

import android.location.Location
import java.util.Calendar
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Splits a day into dawn / day / dusk / night around sunrise and sunset
 * (sunrise equation, ~1 minute accuracy). Falls back to 06:30 / 18:30 without a location.
 */
object SunTimes {
    val PHASES = listOf("dawn", "day", "dusk", "night")
    private const val TWILIGHT = 45 * 60_000L

    class DayPlan(val rise: Long, val set: Long, val fromSun: Boolean) {
        val boundaries get() = longArrayOf(rise - TWILIGHT, rise + TWILIGHT, set - TWILIGHT, set + TWILIGHT)

        fun phaseAt(t: Long): String {
            val b = boundaries
            return when {
                t < b[0] -> "night"
                t < b[1] -> "dawn"
                t < b[2] -> "day"
                t < b[3] -> "dusk"
                else -> "night"
            }
        }
    }

    fun plan(now: Long, loc: Location?): DayPlan {
        val noon = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        if (loc != null) riseSet(noon, loc.latitude, loc.longitude)?.let { return DayPlan(it.first, it.second, true) }
        val midnight = noon - 12 * 3_600_000L
        return DayPlan(midnight + 390 * 60_000L, midnight + 1110 * 60_000L, false)
    }

    fun nextBoundary(now: Long, loc: Location?): Long =
        plan(now, loc).boundaries.firstOrNull { it > now } ?: plan(now + 86_400_000L, loc).boundaries[0]

    /** Sunrise/sunset (epoch ms) for the solar day around [noon]; null in polar day/night. */
    fun riseSet(noon: Long, lat: Double, lon: Double): Pair<Long, Long>? {
        val jd = noon / 86_400_000.0 + 2440587.5
        val n = floor(jd - 2451545.0 + 0.0008)
        val jStar = n - lon / 360.0
        val m = Math.toRadians((357.5291 + 0.98560028 * jStar) % 360)
        val c = 1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m)
        val lambda = Math.toRadians((Math.toDegrees(m) + c + 180 + 102.9372) % 360)
        val transit = 2451545.0 + jStar + 0.0053 * sin(m) - 0.0069 * sin(2 * lambda)
        val decl = asin(sin(lambda) * sin(Math.toRadians(23.4397)))
        val phi = Math.toRadians(lat)
        val cosH = (sin(Math.toRadians(-0.833)) - sin(phi) * sin(decl)) / (cos(phi) * cos(decl))
        if (cosH !in -1.0..1.0) return null
        val h = Math.toDegrees(acos(cosH)) / 360.0
        return toMillis(transit - h) to toMillis(transit + h)
    }

    private fun toMillis(julian: Double) = ((julian - 2440587.5) * 86_400_000.0).roundToLong()
}
