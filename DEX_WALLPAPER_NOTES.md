# How the Samsung DeX background works (SM-F966U, Android 17, One UI Home 18.0.07.59)

Source: `dumpsys wallpaper`, `pm`, and string analysis of the pulled APKs/jars
(launcher, Wallpaper & Style, SystemUI, framework.jar, services.jar, Routines).
Nothing was decompiled to Java; method bodies are not verified.

## Components

| Piece | Package / class | Role |
|---|---|---|
| DeX launcher ("second launcher") | `com.sec.android.app.launcher` → `com.honeyspace.dexservice.SecondaryLauncher` (SECONDARY_HOME) | Icons, taskbar. Does **not** draw the background; it only reads wallpaper colors (`DexWallpaperColorUpdater`). |
| Wallpaper store | `system_server` `WallpaperManagerService` (Samsung `WallpaperDataManager`) | Keeps a separate wallpaper slot per display type. |
| Renderer | `com.android.systemui/.wallpapers.ImageWallpaper` | Default engine that draws a static DeX image. |
| Picker | `com.samsung.android.app.dressroom` (Wallpaper & Style): `DexWallpaperSettingActivity`, `setDexWallpaper` | The UI that writes the DeX slot today. |

## The DeX slot

The `which` bit flags extend AOSP's `FLAG_SYSTEM=1`, `FLAG_LOCK=2`:

| Slot | which | Files in `/data/system/users/0/` |
|---|---|---|
| Phone home | 5 (SYSTEM\|4 main display) | — (live: `LayeredWallpaperService`) |
| **DeX home** | **9 (SYSTEM\|`FLAG_DISPLAY_DEX`=8)** | `wallpaper_desktop_orig`, `wallpaper_desktop` (crop), `wallpaper_desktop_info.xml` |
| DeX lock | 10 (LOCK\|DEX) | `wallpaper_lock_images/wallpaper_desktop_lock[_orig]` |
| Cover/sub | 17 (SYSTEM\|`FLAG_DISPLAY_SUB`=16) | `wallpaper_sub_display*` |
| Virtual display | 33 (`FLAG_DISPLAY_VIRTUAL`=32) | `wallpaper_virtual_display*` |

The current DeX image is 2112×1408, set by dressroom on 2026-10-06 (`mBindSource=SET_STATIC`, `mMode=8`). Its metadata is in `mExternalParams` (`isNight`, `isOutdoor`, `cropHints`).

## Points that matter for a dynamic replacement

1. **Live wallpapers are supported on the DeX slot.** Strings found: `HOME_LIVE_WALLPAPER_DEX` (dressroom), "Dex non-system wallpaper" (services), `persist.wm.debug.desktop_support_live_wallpaper` (not set on this device). So a custom `WallpaperService` bound to `which=9` could redraw itself whenever it likes.
2. **The service checks the `which` flags.** It logs "which is not allowed!" and uses `com.samsung.android.wallpaper.utils.WhichChecker`. We have not yet tested whether a third-party app may set `FLAG_DISPLAY_DEX`.
3. **Routines has no DeX wallpaper action.** It only has home, lock and cover wallpaper actions (`ChangeHomeScreenWallpaper[FoldMain]`, `ChangeCoverScreenWallpaper`, …). To get Routines into the loop, our own app has to receive a trigger from it, for example through a Routines app action, an "open app/shortcut" action, or a broadcast/intent from the Routines Plus/Tasker bridges.

## Candidate approaches (most to least preferred)

- **A. Our own app sets a static image.** Call `WallpaperManager.setBitmap(bmp, null, true, 1 or 8)` from our app on time, location and Routines triggers. This is the simplest option, if `WhichChecker` lets it through.
- **B. Our own live wallpaper on the DeX slot.** The engine picks the image from time, location and Routines state with no re-sets. Applying it to DeX likely needs dressroom's picker (live-wallpaper-for-DeX path) or a signature API, which we need to test.
- **C. ADB/Shizuku fallback.** Use a privileged shell to push the image (Shizuku runs as shell uid, so check whether `cmd wallpaper` or the binder accepts which=9 from shell).

## Probe results (2026-10-30, `probe/` APK, normal third-party app)

| Test | Result |
|---|---|
| `getWallpaperId(9)` / `getWallpaperInfo(9)` | Works, needs no permission |
| `getWallpaperFile(9)` | Needs All files access (`MANAGE_EXTERNAL_STORAGE`); otherwise SecurityException |
| **`setBitmap(bmp, null, true, 9)`** | **Works with only `SET_WALLPAPER`.** The service logs `setWallpaper … callingPackage=dev.dexwp.probe which=0x9`, writes `wallpaper_desktop_orig`, rebinds ImageWallpaper. Phone home (1/5) and cover (17) are untouched. |
| `setBitmap(…, 8)` | IllegalArgumentException "Must specify a valid wallpaper category": DEX has to be OR'd with SYSTEM or LOCK |
| `setWallpaperComponentWithFlags(cn, 9)` | SecurityException: needs `SET_WALLPAPER_COMPONENT` (signature) |
| `setStream(in, null, true, 9)` | Works (used for the restore) |

Samsung APIs that are visible to apps: `getBitmapForDex()`, `semGetWallpaperComponent(which,user)`, `semGetWallpaperType(which)`, `semSetUri(Uri,bool,which,…)`, and `IWallpaperManager.isDesktopMode()`.

## Crop rect: how Android/Samsung applies it (verified)

The picker passes the **full original image plus one crop rect** (`visibleCropHint`). It does not pre-crop the image. `system_server`'s `WallpaperCropper.generateCrop` then does the following:

1. Stores the original as `*_orig` and the rect as `mCropHint`.
2. Cuts `*_orig` at `cropHint` and writes the result to the crop file. That file is what `ImageWallpaper` draws.
3. If the cut area is larger than the device's largest display dimension (2184 px on this Fold), it downsamples it. Example: the 4960×3100 lock cut became a 3494×2184 target and a 3495×2185 file. The 2112×1320 home cut was not scaled.

Samsung's own DeX crops are **16:10 landscape**, full width and centred vertically. For example, home `0,87–2112,1407` on a 2112×1408 image, and lock `0,619–4960,3719` on a 4960×4133 image. The extra `cropHints` JSON in `mExternalParams` holds alternative rects for the other screen shapes; the cropper ignores it ("user defined crops: {}").

Reproduce it like this:

```java
Rect hint = centredCrop(srcW, srcH, 16, 10);              // or the DeX monitor's aspect ratio
wm.setBitmap(bitmap, hint, true, FLAG_SYSTEM | FLAG_DISPLAY_DEX);   // or setStream(in, hint, true, which)
```

Both calls were verified to produce byte-for-byte the same `mCropHint` and crop-file size as dressroom. `setBitmapWithCrops(Bitmap, Map<Point,Rect>, …)` and `setStreamWithCrops(…)` also exist, for per-screen-size crops, but have not been tested. Read the current rect back with `semGetWallpaperCropHint(which)`.

## Lock screens

| Target | which | Status |
|---|---|---|
| **DeX lock** | **10 (LOCK\|DEX)** | **Works**: `setBitmap(bmp, hint, true, 10)`. Files go to `wallpaper_lock_images/wallpaper_desktop_lock[_orig]`. The phone lock is untouched. |
| Phone main lock | 2 / 6 (LOCK\|PHONE) | Not tested. Currently the slot has no image file (`mBindSource=UNKNOWN`), and One UI's lock screen editor layers its own clock and effects on top. Writing to it could affect the user's lock screen setup. |
| Cover lock | 18 (LOCK\|SUB) | Currently Samsung's live `LayeredWallpaperService`. Not tested. |

Backup and restore: `getWallpaperFile(which, false)` (public on this build) returns the **original** file and `getWallpaperFile(which)` returns the crop. Both need All files access. Restore with `setStream(orig, savedHint, true, which)`. This was verified to bring back the identical original and crop on both DeX slots.

**Conclusion:** use approach A. An ordinary app can call `setBitmap(…, 9)` from WorkManager, AlarmManager or a broadcast receiver, triggered by time, location or a Routines action. Approach B (our own live wallpaper on DeX) can only happen through the system picker, if at all. Not yet verified: the image visibly showing on an active DeX session (DeX was off: `isDexEnabled=false`).

## Samsung Routines plugin (SDK v3), as reimplemented in Deskmat
- Discovery: `<application>` meta-data `com.samsung.android.sdk.routines.v3.meta.ACTION` / `.CONDITION` → XML of `<action|condition tag category label description icon><configuration targetUi=…/>`. No allowlist or signature check. Routines does a full rescan only at boot, on locale change, or for a fresh install; `adb install -r` alone isn't picked up.
- Provider: class must be `com.samsung.android.sdk.routines.v3.internal.RoutineSdkContentProvider`, authority `<pkg>.provider.routines.v3`, permission `com.samsung.android.permission.sdk.routines.v3.ACCESS_ROUTINES`, meta-data `…v3.version_code` (int, ≥30104).
- `call(method, null, extras{type=action|condition, tag, instanceId, parameterValues})`: performAction/recoverAction → `resultInt` (1 ok); isSatisfied → 1/0; getLabelParam → `labelParams`; getParameterRepresentation → `{"labelWithFormat":…,"linkedParameterMap":{}}`; isValid/isSupport → 1.
- parameterValues: JSON object whose values are JSON strings `{"TYPE":"STRING|BOOLEAN|…","VALUE":v}`. A custom targetUi activity returns RESULT_OK with `intent_params` (same JSON) and `label_params`.
- Conditions: call `notifyChange(content://<pkg>.provider.routines.v3/<tag>)`; the host then calls isSatisfied.

## One UI colour palette
One UI overrides only `system_accent1/2/3` and `system_neutral1/2` with the user's palette. The Android 14 role tokens (`system_primary_dark`, …) keep their stock values, so Compose's `dynamic*ColorScheme` shows the wrong colours. Build the scheme from the palette tones instead (see `ui/Common.kt`).
