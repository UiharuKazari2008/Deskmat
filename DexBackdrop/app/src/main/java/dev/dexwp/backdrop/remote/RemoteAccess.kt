package dev.dexwp.backdrop.remote

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.security.MessageDigest

/** What an outside app may do. [CONTROL] includes [READ]. */
enum class Scope(val key: String) {
    READ("read"),
    CONTROL("control");

    companion object {
        fun of(s: String?) = entries.firstOrNull { it.key.equals(s, true) }
    }
}

/** An app the user allowed, pinned to the signing certificate it had then. */
data class Grant(val pkg: String, val scope: Scope, val certSha256: String, val at: Long, val lastUsed: Long = 0)

/**
 * Apps the user let read or control Deskmat through [RemoteProvider]. Granted only in
 * [AccessRequestActivity] (the user's own tap); revoked in Settings. A grant is tied to the
 * app's signing certificate, so another app installed later under the same package name gets
 * nothing.
 */
object RemoteAccess {
    private const val PREFS = "remote_access"
    private val _grants = MutableStateFlow<List<Grant>>(emptyList())
    val grants: StateFlow<List<Grant>> get() = _grants
    @Volatile private var loaded = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    private fun load(c: Context) {
        if (loaded) return
        _grants.value = prefs(c).all.mapNotNull { (pkg, v) ->
            runCatching {
                val o = JSONObject(v as String)
                Grant(pkg, Scope.of(o.getString("scope"))!!, o.getString("cert"), o.optLong("at"), o.optLong("used"))
            }.getOrNull()
        }.sortedBy { it.pkg }
        loaded = true
    }

    fun list(c: Context): List<Grant> { load(c); return _grants.value }

    @Synchronized
    private fun save(c: Context, g: Grant) {
        load(c)
        prefs(c).edit().putString(g.pkg, JSONObject().put("scope", g.scope.key).put("cert", g.certSha256).put("at", g.at).put("used", g.lastUsed).toString()).apply()
        _grants.value = (_grants.value.filter { it.pkg != g.pkg } + g).sortedBy { it.pkg }
    }

    fun grant(c: Context, pkg: String, scope: Scope): Boolean {
        val cert = certOf(c, pkg) ?: return false
        save(c, Grant(pkg, scope, cert, System.currentTimeMillis()))
        return true
    }

    @Synchronized
    fun revoke(c: Context, pkg: String) {
        load(c)
        prefs(c).edit().remove(pkg).apply()
        _grants.value = _grants.value.filter { it.pkg != pkg }
    }

    /**
     * The scope [pkg] (calling as [uid]) holds now, or null. Deskmat itself, adb shell and root
     * hold everything (they can already do this through the command broadcast).
     */
    fun scopeOf(c: Context, uid: Int, pkg: String?): Scope? {
        if (uid == Process.myUid() || uid == Process.SHELL_UID || uid == Process.ROOT_UID) return Scope.CONTROL
        if (pkg == null) return null
        // The package must really belong to the calling uid.
        if (c.packageManager.getPackagesForUid(uid)?.contains(pkg) != true) return null
        val g = list(c).firstOrNull { it.pkg == pkg } ?: return null
        if (!signedWith(c, pkg, g.certSha256)) return null
        val now = System.currentTimeMillis()
        if (now - g.lastUsed > 60_000) save(c, g.copy(lastUsed = now))
        return g.scope
    }

    fun allows(held: Scope?, needed: Scope) = held != null && held.ordinal >= needed.ordinal

    /** SHA-256 (hex) of the app's current signing certificate. */
    fun certOf(c: Context, pkg: String): String? = runCatching {
        val info = c.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return null
        val sig = if (info.hasMultipleSigners()) info.apkContentsSigners.first() else info.signingCertificateHistory.last()
        MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** True when the app is still signed with that certificate (key rotation counts). */
    private fun signedWith(c: Context, pkg: String, hex: String): Boolean = runCatching {
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        c.packageManager.hasSigningCertificate(pkg, bytes, PackageManager.CERT_INPUT_SHA256)
    }.getOrDefault(false)

    /** The app's name, or its package name when Android doesn't let Deskmat see it. */
    fun label(c: Context, pkg: String): String = runCatching {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
