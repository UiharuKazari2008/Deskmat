# Deskmat remote API

Other apps can read what Deskmat shows on each screen, get the image files, list groups, images and flags, and drive it (next image, set an image or group, Automatic, Shuffle, flags). The user has to allow each app first.

Code: `app/src/main/java/dev/dexwp/backdrop/remote/` (`RemoteProvider`, `RemoteAccess`, `AccessRequestActivity`).

## 1. Setup in the client app

Android 11+ hides other apps unless you declare them:

```xml
<queries>
    <package android:name="dev.dexwp.backdrop" />
</queries>
```

## 2. Asking for access

There are two levels:
- `read`: status, groups, images, flags, and image files.
- `control`: everything in `read`, plus the `call()` methods.

```kotlin
val ask = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
    val scope = r.data?.getStringExtra("scope")   // "read" / "control", or null if refused
}
ask.launch(Intent("dev.dexwp.backdrop.REQUEST_ACCESS")
    .setPackage("dev.dexwp.backdrop")
    .putExtra("scope", "control"))
```

- Start it with `startActivityForResult` or the Activity Result API, not `startActivity`. Deskmat learns which app is asking from Android, through the calling package.
- If the app already holds the scope, the result comes back at once and no dialog is shown.
- The grant is tied to the app's signing certificate. The user can remove it in **Settings → Connected apps**.
- To check access without showing UI, call `contentResolver.call(BASE, "access", null, null)?.getString("scope")`. It returns null when the app has no access.

A call without access throws `SecurityException`.

The adb shell (`content query …`) and root always have full access. They could already send the command broadcast.

## 3. Reading

The base URI is `content://dev.dexwp.backdrop.remote`. Slots are `home` and `lock` (DeX), `main` and `mainlock`, `cover` and `coverlock`. `status` lists only the slots this phone has. Booleans are `0`/`1`.

| URI | Rows |
|---|---|
| `/status` | One row per slot: `slot, label, screen, lock, wallpaper_id, wallpaper_name, group_id, group_name, shuffle, reason, changed_at, pinned, pin, automatic, next_id, next_name, full_uri, cropped_uri` |
| `/current/<slot>` | The same row for one slot |
| `/groups[?slot=]` | `id, slot, name, command, shuffle, global, links, unassigned, image_count, active_on, triggers, current_id`. With `slot`: every group that can be active there, plus its Unassigned group |
| `/groups/<id>` | One group. `<id>` may also be the group's command name or display name |
| `/groups/<id>/images[?slot=]` | The group's images in order, with `position, skipped, current`. Global groups need `slot` |
| `/images[?slot=]` | Every image (or those that can show on the slot): `_id, id, slot, name, command, width, height, crop, crop_land, groups, source_path, media_id, media_uri, internal_path, full_uri, cropped_uri, upscaled` |
| `/images/<id>[?slot=]` | One image. `slot` picks which slot's crop `crop` and `cropped_uri` describe (linked groups) |
| `/flags` | `name, on` |

- `pin` is a wallpaper id, or `group:<id>` when a whole group is pinned.
- `crop`/`crop_land` are `left,top,right,bottom` in the full image's pixels. Null means no crop was saved, so Deskmat uses a centred crop.
- `source_path` is where the image was imported from on shared storage, when known. `media_id`/`media_uri` are its MediaStore entry, when one exists.
- `internal_path` is Deskmat's private copy. Other apps can't open it, so use `full_uri`.

## 4. Image files

Open any of these with `contentResolver.openInputStream(uri)`. They're read-only.

| URI | File |
|---|---|
| `/images/<id>/full` | The full image Deskmat applies (after upscaling, if it was upscaled) |
| `/images/<id>/cropped[?slot=&orientation=landscape&width=]` | JPEG of exactly what the slot shows: the saved crop, else the centred one. `width` scales it down |
| `/current/<slot>/full`, `/current/<slot>/cropped` | The same, for whatever the slot shows now |

`orientation=landscape` gives the landscape crop of a rotating phone screen. `query()` on a file URI returns `_display_name` and `_size`.

## 5. Watching for changes

```kotlin
contentResolver.registerContentObserver(Uri.parse("content://dev.dexwp.backdrop.remote/status"), true, observer)
```

Deskmat notifies `/status` and `/current` when an image, pin, active group, Automatic or the next image changes. It also notifies `/groups`, `/images` and `/flags` when those change.

## 6. Control (`control` scope)

```kotlin
val r = contentResolver.call(BASE, "next", null, bundleOf("slot" to "cover"))
r?.getBoolean("ok"); r?.getString("reply")
```

| method | arg | extras | Does |
|---|---|---|---|
| `next` | – | `slot` (none = all) | Advance the active group now (in order, or random with Shuffle) |
| `set` | image or group (id, command name or name) | `slot` | Pin an image, or make a group active |
| `clear` | – | `slot` | Drop pins; triggers decide again |
| `flag` | flag name | `state` on/off/toggle (default on) | Set a flag |
| `automatic` | – | `slot`, `state` on/off | Automatic switch |
| `shuffle` | group (or none = the slot's active group) | `slot`, `state` on/off/toggle (default toggle) | A group's Shuffle |
| `refresh` | – | – | Re-apply everything |
| `command` | – | `cmd`, `arg`, `target`, `state` | Any command from the Termux/broadcast vocabulary |

Calls wait up to 5 s for the wallpaper to be applied. Run them off the main thread. `reply` holds the result text, or `error: …`.

## Note

The older command broadcast (`dev.dexwp.backdrop.COMMAND` to `CommandReceiver`) is still exported with no permission, so any app can send it commands without asking. It stays that way for Termux and Tasker.
