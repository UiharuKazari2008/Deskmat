package dev.dexwp.backdrop.system

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import dev.dexwp.backdrop.data.Library
import dev.dexwp.backdrop.engine.Engine
import dev.dexwp.backdrop.engine.Locator
import dev.dexwp.backdrop.model.Condition
import java.util.Locale

/**
 * Event-driven location: every Place condition becomes a Play services geofence, so the OS
 * (low-power hardware where available) wakes us only on enter/exit; no polling fixes.
 * Sun times use a passive location subscription that reuses fixes other apps request.
 */
object Geofences {
    private const val MAX = 100 // Play services limit per app

    fun id(p: Condition.Place) = String.format(Locale.US, "p_%.5f_%.5f_%d", p.lat, p.lon, p.radius.toInt())

    fun available(c: Context) =
        c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            Locator.hasBackground(c)

    /** Re-registers geofences for all places in the gallery. Safe to call often. */
    @SuppressLint("MissingPermission")
    fun sync(c: Context) {
        val places = Library.get(c).groups.flatMap { it.triggers }.flatMap { it.conditions }
            .filterIsInstance<Condition.Place>().distinctBy(::id).take(MAX)
        val client = LocationServices.getGeofencingClient(c)
        val pi = geofenceIntent(c)
        if (places.isEmpty() || !available(c)) {
            client.removeGeofences(pi)
            setStatus(c, if (places.isEmpty()) "No places in triggers" else "Off: needs precise location “all the time”")
            Library.update(c) { it.copy(geofencing = false) }
            return
        }
        val fences = places.map {
            Geofence.Builder().setRequestId(id(it))
                .setCircularRegion(it.lat, it.lon, it.radius.coerceAtLeast(100f))
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                .setNotificationResponsiveness(60_000)
                .build()
        }
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_EXIT)
            .addGeofences(fences).build()
        client.removeGeofences(pi).continueWithTask { client.addGeofences(request, pi) }
            .addOnSuccessListener {
                val ids = fences.map { it.requestId }.toSet()
                // Forget stale "inside" ids for places that no longer exist.
                Library.update(c) { s -> s.copy(geofencing = true, inside = s.inside intersect ids) }
                setStatus(c, "Active for ${fences.size} place${if (fences.size == 1) "" else "s"}")
            }
            .addOnFailureListener { e ->
                Log.w(Engine.TAG, "geofences: $e")
                Library.update(c) { it.copy(geofencing = false) }
                setStatus(c, "Failed: ${e.message} (falling back to last known location)")
            }
        startPassive(c)
    }

    /** Cached fixes from other apps' requests, for sunrise/sunset and the no-geofence fallback. */
    @SuppressLint("MissingPermission")
    fun startPassive(c: Context) {
        if (!Locator.hasPermission(c)) return
        val pi = PendingIntent.getBroadcast(
            c, 2, Intent(c, PassiveLocationReceiver::class.java),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching {
            c.getSystemService(LocationManager::class.java)
                .requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 15 * 60_000L, 1000f, pi)
        }.onFailure { Log.w(Engine.TAG, "passive location: $it") }
    }

    private fun geofenceIntent(c: Context) = PendingIntent.getBroadcast(
        c, 1, Intent(c, GeofenceReceiver::class.java),
        // Play services fills in the event extras, so this must be mutable.
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun setStatus(c: Context, s: String) {
        Library.update(c) { it.copy(geofenceStatus = s) }
        Log.i(Engine.TAG, "geofencing: $s")
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w(Engine.TAG, "geofence error ${event.errorCode}")
            return
        }
        val ids = event.triggeringGeofences.orEmpty().map { it.requestId }.toSet()
        val entered = event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER
        event.triggeringLocation?.let { Locator.remember(it) }
        Library.update(c) { s -> s.copy(inside = if (entered) s.inside + ids else s.inside - ids) }
        Log.i(Engine.TAG, "geofence ${if (entered) "enter" else "exit"} $ids")
        ApplyJob.request(c, "geofence")
    }
}

class PassiveLocationReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        val loc = intent.getParcelableExtra<Location>(LocationManager.KEY_LOCATION_CHANGED) ?: return
        Locator.remember(loc)
    }
}
