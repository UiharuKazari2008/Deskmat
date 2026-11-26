package dev.dexwp.backdrop.engine

import android.content.Context
import dev.dexwp.backdrop.data.Library
import dev.dexwp.backdrop.model.Json
import dev.dexwp.backdrop.model.Target
import dev.dexwp.backdrop.model.Wallpaper
import dev.dexwp.backdrop.system.ApplyJob
import dev.dexwp.backdrop.system.Shortcuts
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * One command vocabulary shared by Termux/adb broadcasts, shortcuts and the Routines plugin.
 *
 * Slots: home, lock (DeX), main, mainlock, cover, coverlock.
 *
 *   set <name> [slot]          pin a gallery wallpaper, or make a group active (command name, name or id)
 *   clear [slot]               drop pins, back to triggers
 *   flag <name> [on|off|toggle], unflag <name>
 *   file <path> [slot]         import an image file into the gallery and pin it
 *   automatic [slot] [on|off|next]  toggle Automatic, or advance the active group now (default); alias shuffle
 *   next [slot]                advance the active group now
 *   downsample off|dex|<width>       pre-scale applied images on the phone
 *   upscale <name> [slot]      4× Real-ESRGAN (anime) upscale of a gallery image, on the phone
 *   refresh | status | list | flags
 *
 * State changes happen right away; the wallpaper is applied by [ApplyJob]. The reply waits up to
 * [waitMs] for that, so broadcasts stay inside their time limit.
 */
object Commands {
    const val DEFAULT_WAIT_MS = 8_000L

    fun execute(c: Context, cmd: String, arg: String?, target: Target?, state: String? = null, waitMs: Long = DEFAULT_WAIT_MS): String {
        val s = Library.get(c)
        val all = Target.entries.toSet()
        fun apply(reason: String, force: Set<Target> = emptySet()) = applyAndWait(c, reason, force, waitMs)
        return when (cmd.lowercase()) {
            "set", "apply" -> {
                // A wallpaper first, then a group (its active image is kept; triggers take over when they change).
                val wp = arg?.let { s.find(it, target) }
                if (wp != null) {
                    Library.update(c) { it.copy(pins = it.pins + (wp.target to wp.id)) }
                    return apply("set", setOf(wp.target))
                }
                val g = arg?.let { s.findGroup(it.removePrefix(Json.GROUP_PIN), target) } ?: return "error: no wallpaper or group named “$arg”"
                // A linked group is pinned on every slot it drives, a global one on every slot it has images on.
                val slots = (if (g.global) s.slotsWithImages(g).toSet() else g.targets).ifEmpty { g.targets }
                Library.update(c) { it.copy(pins = it.pins + slots.associateWith { Json.GROUP_PIN + g.id }) }
                apply("set", slots)
            }
            "clear", "auto" -> {
                val targets = target?.let { setOf(it) } ?: all
                Library.update(c) { it.copy(pins = it.pins - targets) }
                apply("clear", targets)
            }
            "flag", "unflag" -> {
                val name = arg?.trim()?.takeIf { it.isNotEmpty() } ?: return "error: flag name missing"
                val current = s.flags[name] ?: false
                val on = when {
                    cmd.equals("unflag", true) -> false
                    state == null || state.equals("on", true) || state == "1" || state.equals("true", true) -> true
                    state.equals("toggle", true) -> !current
                    else -> false
                }
                Library.update(c) { it.copy(flags = it.flags + (name to on)) }
                "flag $name=${if (on) "on" else "off"}\n" + apply("flag")
            }
            "file" -> {
                val f = File(arg ?: return "error: path missing")
                if (!f.canRead()) return "error: cannot read $f (grant All files access to Deskmat)"
                val imp = Images.importFile(c, f)
                val t = target ?: Target.HOME
                val wp = Wallpaper(UUID.randomUUID().toString(), t, f.nameWithoutExtension, imp.file.path, imp.width, imp.height, source = imp.source)
                Library.update(c) { it.copy(wallpapers = it.wallpapers + wp, pins = it.pins + (t to wp.id)) }
                Shortcuts.publish(c)
                apply("file", setOf(t))
            }
            "automatic", "shuffle", "next" -> {
                val targets = target?.let { setOf(it) } ?: all
                when (state?.lowercase()) {
                    "on" -> Library.update(c) { it.copy(autoOn = it.autoOn + targets) }
                    "off" -> Library.update(c) { it.copy(autoOn = it.autoOn - targets) }
                    else -> Library.update(c) { it.copy(advanceDue = it.advanceDue + targets, advanceManual = it.advanceManual + targets, pins = it.pins - targets) }
                }
                apply("auto")
            }
            "downsample" -> {
                val v = arg?.lowercase()?.removeSuffix("px")
                if (v == null || (v != "off" && v != "dex" && v.toIntOrNull() == null)) return "error: downsample off|dex|<width>"
                Library.update(c) { it.copy(downsample = v) }
                "downsample=$v\n" + apply("downsample", all)
            }
            "upscale" -> {
                val wp = arg?.let { s.find(it, target) } ?: return "error: no wallpaper named “$arg”"
                if (!Upscaler.canUpscale(wp)) return "error: ${wp.name} is already ${wp.width}×${wp.height}"
                // Takes up to a minute: runs on its own thread and re-applies when done.
                kotlin.concurrent.thread(name = "deskmat-upscale") {
                    Upscaler.upscale(c, wp) { _, _ -> true }
                        .onSuccess { saved -> Engine.runAsync(c, "upscale"); saved?.let { android.util.Log.i(Engine.TAG, "upscaled copy saved to $it") } }
                        .onFailure { android.util.Log.w(Engine.TAG, "upscale ${wp.name} failed", it) }
                }
                "upscaling ${wp.name} (${wp.width}×${wp.height} → ${wp.width * 4}×${wp.height * 4}); check with `status`"
            }
            "refresh" -> apply("refresh", all)
            "status" -> Target.entries.joinToString("\n") { t ->
                val st = s.status[t]
                "${t.key}: ${s.byId(st?.wallpaperId)?.name ?: "-"} — ${st?.reason ?: "not run"}" +
                    (s.pins[t]?.let { " [pinned]" } ?: "") + (if (t in s.autoOn) " [automatic]" else "") +
                    (if (t in s.dexPending) " [re-applies when DeX starts]" else "")
            }
            "list" -> Target.entries.flatMap { t -> (s.groupsFor(t) + s.unassigned(t)).map { t to it } }.mapNotNull { (t, g) ->
                val wps = s.inGroup(g, t).ifEmpty { return@mapNotNull null }
                "${t.key} · ${g.name}" + (if (g.global) " (global)" else "") + (g.command?.let { " [⌘ $it]" } ?: "") + (if (g.shuffle) " (shuffle)" else "") +
                    (if (g.triggers.isNotEmpty()) " · ${g.triggers.size} trigger(s)" else "") + "\n" +
                    wps.joinToString("\n") { w -> "  ${w.command ?: "-"}\t${w.name}" }
            }.joinToString("\n").ifEmpty { "(gallery is empty)" }
            "flags" -> s.flags.entries.joinToString("\n") { "${it.key}=${if (it.value) "on" else "off"}" }.ifEmpty { "(no flags)" }
            else -> "error: unknown command “$cmd”. Commands: set, clear, flag, unflag, file, automatic, next, downsample, upscale, refresh, status, list, flags"
        }
    }

    /**
     * Runs the engine in this process right away (a job can be held back for minutes, e.g. while
     * the phone is hot). If it hasn't finished within [waitMs], a job is queued as a backup in
     * case the process goes away before the run completes; a second run finds nothing to change.
     */
    private fun applyAndWait(c: Context, reason: String, force: Set<Target>, waitMs: Long): String {
        val result = CompletableFuture<String>()
        Engine.runAsync(c, reason, force) { result.complete(Engine.lastSummary) }
        return try {
            result.get(waitMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            ApplyJob.request(c, reason, force)
            "applying in the background (up to a minute while DeX is off); check with `status`"
        }
    }
}
