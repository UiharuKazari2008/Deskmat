package dev.dexwp.backdrop.routines

import android.content.Context
import android.net.Uri
import dev.dexwp.backdrop.data.Library
import dev.dexwp.backdrop.model.Json
import dev.dexwp.backdrop.model.Target
import org.json.JSONObject

/**
 * Routines SDK v3 parameter encoding: an object whose values are JSON strings of
 * {"TYPE": "STRING"|"BOOLEAN"|..., "VALUE": v}.
 */
object RoutineParams {
    const val ACTION_SET = "dexwp_set_wallpaper"
    const val ACTION_FLAG = "dexwp_set_flag"
    const val CONDITION_FLAG = "dexwp_flag_is"
    const val CONDITION_SHOWING = "dexwp_showing"
    /** Wallpaper id meaning "resume automatic" for the slot in "target". */
    const val AUTO = "auto"
    /** Wallpaper id meaning "advance the active group" for the slot in "target". */
    const val NEXT = "next"

    fun parse(json: String?): Map<String, Any?> {
        if (json.isNullOrBlank()) return emptyMap()
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        return o.keys().asSequence().associateWith { k ->
            val raw = o.opt(k)
            val inner = (raw as? String)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: raw as? JSONObject
            inner?.opt("VALUE") ?: raw
        }
    }

    fun encode(values: Map<String, Any>): String {
        val o = JSONObject()
        values.forEach { (k, v) ->
            val type = when (v) { is Boolean -> "BOOLEAN"; is Number -> "NUMBER"; else -> "STRING" }
            o.put(k, JSONObject().put("TYPE", type).put("VALUE", v).toString())
        }
        return o.toString()
    }

    fun target(pv: Map<String, Any?>) = Target.of(pv["target"] as? String)

    fun label(c: Context, tag: String, pv: Map<String, Any?>): String {
        val s = Library.get(c)
        return when (tag) {
            ACTION_SET, CONDITION_SHOWING -> {
                val id = pv["wallpaper"] as? String
                when {
                    id == AUTO -> "Automatic (${target(pv)?.label ?: "all screens"})"
                    id == NEXT -> "Next image (${target(pv)?.label ?: "all screens"})"
                    id?.startsWith(Json.GROUP_PIN) == true -> s.group(id.removePrefix(Json.GROUP_PIN))?.let { "Group ${it.name} (${it.target.label})" }
                    else -> s.byId(id)?.let { "${it.name} (${it.target.label})" }
                } ?: (pv["name"] as? String ?: "Choose a wallpaper")
            }
            ACTION_FLAG, CONDITION_FLAG -> {
                val name = pv["flag"] as? String ?: return "Choose a flag"
                "$name ${if (pv["state"] as? Boolean != false) "on" else "off"}"
            }
            else -> ""
        }
    }

    fun isValid(c: Context, tag: String, pv: Map<String, Any?>): Boolean = when (tag) {
        ACTION_SET, CONDITION_SHOWING -> (pv["wallpaper"] as? String).let {
            it == AUTO || it == NEXT || Library.get(c).byId(it) != null ||
                (it?.startsWith(Json.GROUP_PIN) == true && Library.get(c).group(it.removePrefix(Json.GROUP_PIN)) != null)
        }
        ACTION_FLAG, CONDITION_FLAG -> !(pv["flag"] as? String).isNullOrBlank()
        else -> false
    }

    /** Tells the Routines host to re-query a condition (it watches this URI while the condition is enabled). */
    fun notifyCondition(c: Context, tag: String) {
        c.contentResolver.notifyChange(Uri.parse("content://${c.packageName}.provider.routines.v3/$tag"), null)
    }
}
