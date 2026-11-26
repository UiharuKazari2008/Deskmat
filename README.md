# DexPaper

Dynamic wallpapers for **Samsung DeX** and Galaxy foldables.

The main project is **Deskmat** (`DexBackdrop/`), an Android app that manages the wallpaper of every Samsung screen separately: the DeX desktop and DeX lock screen, the main screen and the cover screen. It changes them on a schedule, by place, by time of day or from Samsung Modes and Routines.

Stock One UI has only one static DeX wallpaper, and Routines has no action to change it. Deskmat writes Samsung's DeX wallpaper slot (`which = SYSTEM | FLAG_DISPLAY_DEX = 9`) directly, with only the normal `SET_WALLPAPER` permission. [`DEX_WALLPAPER_NOTES.md`](DEX_WALLPAPER_NOTES.md) explains how that works.

## Features

- **Per-screen slots:** DeX desktop, DeX lock, main, main lock, cover and cover lock, each with its own image or group.
- **Groups with triggers.** A group becomes active when all of its conditions hold:
  - time of day and weekdays
  - sun phase (dawn, day, dusk, night) at your location
  - a place (geofence)
  - light or dark mode
  - named flags you set from Routines, shortcuts, widgets or the shell
- **Automatic:** moves to the group's next image when DeX connects or disconnects, on an interval, when the screen turns off, or when you fold or unfold. It can also wait until the current image has been seen, or shown for a minimum time.
- **Shuffle**, and crop editing for each screen an image is used on, with separate landscape crops for screens that rotate.
- **On-device 4× upscaling** with Real-ESRGAN (anime model) through ncnn and Vulkan, and optional downsampling for large images.
- **Samsung Modes and Routines plugin:** actions *Set Deskmat wallpaper* and *Set Deskmat flag*, and conditions *Deskmat flag* and *DeX wallpaper showing*.
- **Home screen widgets** (Material and One UI styles): *Next wallpaper* and *Slide to change wallpaper*.
- **Remote API** so other apps can read and control Deskmat after you allow them: see [`docs/REMOTE_API.md`](DexBackdrop/docs/REMOTE_API.md).
- **A caption bar built for DeX:** in desktop mode the app draws its tabs and actions in the window's title bar. As the window narrows, they move into the app body in stages.

## Requirements

- A Samsung Galaxy phone with **Android 14+ (One UI 6+)** (minSdk 34). The app was built and tested on a Galaxy Z Fold7 (SM-F966U) with One UI 8 and Android 17.
- An **arm64** phone. The upscaler's native library is built for arm64-v8a only.
- DeX features need Samsung DeX. The other screens work on any supported Galaxy.

## Install

Download `Deskmat-<version>.apk` from the **Releases** page and install it. Your browser or file manager will ask you to allow installs from unknown sources.

Releases are posted to both mirrors:
- GitHub: https://github.com/UiharuKazari2008/DexPaper/releases
- Yozora: https://yozora.bluesteel.737.jp.net/UiharuKazari2008/DexPaper/releases

Optional permissions, each only needed for the feature that uses it:

| Permission | Used for |
|---|---|
| Location (including background) | Place triggers and sunrise/sunset times |
| All files access | Reading the current wallpaper file of a screen, and saving upscaled images |
| Usage access | Detecting when the home screen is shown (the "must be seen" rule) |
| Notifications | The screen-watcher service and job status |

## Command line (Termux / adb / Tasker)

```sh
am broadcast -n dev.dexwp.backdrop/.system.CommandReceiver -a dev.dexwp.backdrop.COMMAND \
    --es cmd set --es arg work [--es target home|lock|main|mainlock|cover|coverlock] [--es state on|off|toggle]
```

| Command | What it does |
|---|---|
| `set <name> [slot]` | Pin an image, or make a group active |
| `clear [slot]` | Drop pins and go back to triggers |
| `flag <name> [on\|off\|toggle]`, `unflag <name>` | Set flags used by triggers |
| `file <path> [slot]` | Import an image file and pin it |
| `next [slot]` | Move the active group to its next image now |
| `automatic [slot] [on\|off\|next]` | Toggle Automatic, or advance (alias `shuffle`) |
| `upscale <name> [slot]` | 4× Real-ESRGAN upscale of a gallery image |
| `downsample off\|dex\|<width>` | Pre-scale applied images |
| `refresh`, `status`, `list`, `flags` | Re-apply, or print the current state |

The reply is printed by `am broadcast`.

## Building

```sh
cd DexBackdrop
./gradlew assembleRelease   # or assembleDebug
```

You need JDK 17+, Android SDK 36, NDK `29.0.14206865` and CMake `3.31.6`. Gradle downloads its own dependencies. The ncnn library and the Real-ESRGAN model are already in the repo.

On Windows, if Gradle fails with *Unable to establish loopback connection*, set:

```sh
export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:/Users/Public"
```

## Repository layout

| Path | Contents |
|---|---|
| `DexBackdrop/` | The Deskmat Android app (Kotlin, Jetpack Compose, Material 3) |
| `DexBackdrop/app/src/main/cpp/` | Native upscaler (ncnn and Real-ESRGAN) |
| `DexBackdrop/docs/` | Remote API and the draft integrations spec |
| `DexBackdrop/tools/` | Icon and widget generator scripts |
| `DEX_WALLPAPER_NOTES.md` | Research notes on how One UI stores and draws the DeX wallpaper |
| `wallpaper-backups/` | Backups of the original DeX wallpaper used while testing |

## Third-party components

Deskmat bundles [ncnn](https://github.com/Tencent/ncnn) and the [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN) `x4plus-anime` model, both under the BSD 3-Clause license. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

Samsung, Galaxy, DeX and One UI are trademarks of Samsung Electronics. This project is not affiliated with Samsung.
