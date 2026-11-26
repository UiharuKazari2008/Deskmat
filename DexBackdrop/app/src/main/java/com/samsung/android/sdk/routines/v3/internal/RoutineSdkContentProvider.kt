package com.samsung.android.sdk.routines.v3.internal

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import dev.dexwp.backdrop.model.Json
import dev.dexwp.backdrop.data.Library
import dev.dexwp.backdrop.engine.Commands
import dev.dexwp.backdrop.engine.Engine
import dev.dexwp.backdrop.routines.RoutineParams

/**
 * Samsung Modes and Routines plugin endpoint (Routines SDK v3 protocol, reimplemented).
 * The host finds it by this exact class name and calls
 * content://<package>.provider.routines.v3 with call(method, null, extras{type, tag, parameterValues, ...}).
 * Actions/conditions are declared in res/xml/routine_actions.xml and routine_conditions.xml.
 */
class RoutineSdkContentProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val c = context!!.applicationContext
        val x = extras ?: Bundle()
        val tag = x.getString("tag").orEmpty()
        val pv = RoutineParams.parse(x.getString("parameterValues"))
        val out = Bundle()
        Log.i(Engine.TAG, "routines call $method type=${x.getString("type")} tag=$tag pv=$pv")
        try {
            when (method) {
                "performAction", "recoverAction" -> {
                    val ok = perform(c, tag, pv, reverse = method == "recoverAction")
                    out.putInt("resultInt", if (ok) 1 else -1)
                    out.putLong("instanceId", x.getLong("instanceId"))
                }
                "isSatisfied" -> out.putInt("resultInt", if (satisfied(c, tag, pv)) 1 else 0)
                "getLabelParam" -> out.putString("labelParams", RoutineParams.label(c, tag, pv))
                "getParameterRepresentation" -> out.putString(
                    "parameterRepresentation",
                    org.json.JSONObject().put("labelWithFormat", RoutineParams.label(c, tag, pv))
                        .put("linkedParameterMap", org.json.JSONObject()).toString(),
                )
                "getCurrentParam" -> out.putString("parameterValues", x.getString("parameterValues") ?: "{}")
                "isValid" -> out.putInt("resultInt", if (RoutineParams.isValid(c, tag, pv)) 1 else -1)
                "isSupport" -> out.putInt("resultInt", 1)
                "getErrorDialogContents" -> out.putBundle("errorDialogContents", Bundle().apply {
                    putString("errorDialogTitle", "Deskmat")
                    putString("errorDialogMessage", "The selected wallpaper or flag no longer exists. Edit this routine step.")
                })
                "onEnabled", "onDisabled" -> Unit // Changes are pushed via notifyChange from Library.update.
                "onMigrate" -> out.putString("migratedParameter", x.getString("parameterValues"))
            }
        } catch (t: Throwable) {
            Log.e(Engine.TAG, "routines call $method failed", t)
            out.putInt("resultInt", -2)
        }
        return out
    }

    private fun perform(c: android.content.Context, tag: String, pv: Map<String, Any?>, reverse: Boolean): Boolean {
        val s = Library.get(c)
        return when (tag) {
            RoutineParams.ACTION_SET -> {
                val target = RoutineParams.target(pv)
                val id = pv["wallpaper"] as? String
                when {
                    // Advancing has nothing to undo when the routine ends.
                    id == RoutineParams.NEXT -> reverse || !Commands.execute(c, "next", null, target, waitMs = 0).startsWith("error")
                    id == RoutineParams.AUTO || reverse -> {
                        // Reverse (routine ended): drop the pin only if it is still ours.
                        val pinned = target?.let { s.pins[it] }
                        if (!reverse || id == RoutineParams.AUTO || pinned == id) {
                            Commands.execute(c, "clear", null, target, waitMs = 0)
                        }
                        true
                    }
                    id?.startsWith(Json.GROUP_PIN) == true -> {
                        val g = s.group(id.removePrefix(Json.GROUP_PIN)) ?: return false
                        !Commands.execute(c, "set", g.id, g.target, waitMs = 0).startsWith("error")
                    }
                    else -> {
                        val wp = s.byId(id) ?: return false
                        !Commands.execute(c, "set", wp.id, wp.target, waitMs = 0).startsWith("error")
                    }
                }
            }
            RoutineParams.ACTION_FLAG -> {
                val name = pv["flag"] as? String ?: return false
                val on = (pv["state"] as? Boolean ?: true) xor reverse
                Commands.execute(c, "flag", name, null, if (on) "on" else "off", waitMs = 0)
                true
            }
            else -> false
        }
    }

    private fun satisfied(c: android.content.Context, tag: String, pv: Map<String, Any?>): Boolean {
        val s = Library.get(c)
        return when (tag) {
            RoutineParams.CONDITION_FLAG -> (s.flags[pv["flag"] as? String] ?: false) == (pv["state"] as? Boolean ?: true)
            RoutineParams.CONDITION_SHOWING -> {
                val wp = s.byId(pv["wallpaper"] as? String) ?: return false
                s.status[wp.target]?.wallpaperId == wp.id
            }
            else -> false
        }
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
