package dev.dexwp.backdrop.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Location without Play services: freshest last-known fix, plus an optional one-shot fresh fix. */
object Locator {
    private const val STALE_MS = 30 * 60_000L
    private var cached: Location? = null

    fun hasPermission(c: Context) =
        c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(c: Context) =
        c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Stores a fix delivered by a passive subscription or geofence event. */
    fun remember(l: Location) {
        if (cached == null || l.time >= cached!!.time) cached = l
    }

    fun current(c: Context, allowFresh: Boolean): Location? {
        if (!hasPermission(c)) return cached
        val lm = c.getSystemService(LocationManager::class.java)
        var best = cached
        try {
            for (p in lm.getProviders(true)) {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.time > best.time) best = l
            }
            if (allowFresh && (best == null || System.currentTimeMillis() - best.time > STALE_MS)) {
                fresh(lm)?.let { best = it }
            }
        } catch (e: SecurityException) {
            Log.w(Engine.TAG, "location: ${e.message}")
        }
        cached = best
        return best
    }

    private fun fresh(lm: LocationManager): Location? {
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { lm.isProviderEnabled(it) } ?: return null
        val latch = CountDownLatch(1)
        var out: Location? = null
        lm.getCurrentLocation(provider, null, Executors.newSingleThreadExecutor()) { out = it; latch.countDown() }
        latch.await(20, TimeUnit.SECONDS)
        return out
    }
}
