# Image source integrations (draft spec)

Status: draft for later work. Nothing here is implemented yet.

## Goal

Let Deskmat use images that live in other galleries the user runs, starting with **Dreamscape** and **Sequenzia**, in two ways:

1. **Single images.** Pick individual images from a source and add them to any group, just like images from Samsung Gallery.
2. **Source groups.** A group gets its images from a source query plus filters (for example "Sequenzia, album X, landscape only, rating ≥ 4"). Deskmat keeps a small local cache and refreshes it, so triggers, shuffle, Automatic, widgets and commands all work as they do for local groups.

## What exists today that this builds on

| Piece | Where | Role for sources |
|---|---|---|
| `Wallpaper` (file, size, crop, group) | `model/Model.kt` | Cached source images stay ordinary `Wallpaper`s with a local file, so the engine, applier and UI need no special cases. |
| `Group` (triggers, shuffle, auto rules, command) | `model/Model.kt` | Gains an optional `source` so its members can be filled in by a provider. |
| `Images.importUri` / `importFile` | `engine/Images.kt` | Every source image goes through the same import (EXIF rotation, size). |
| `Engine.pick` | `engine/Engine.kt` | Picks from `inGroup(g)`. For source groups that list is the local cache. |
| `ApplyJob`, `Scheduler` | `system/` | Background refills reuse the job infrastructure. |

## Model changes

```kotlin
/** Where an image came from, kept so it can be refreshed, de-duplicated and credited. */
data class Origin(
    val provider: String,          // "dreamscape", "sequenzia"
    val remoteId: String,          // the provider's stable id for the image
    val url: String? = null,       // web page for "open in source"
    val meta: Map<String, String> = emptyMap(), // title, artist, tags, rating…
)

/** A group filled from a provider. */
data class SourceSpec(
    val provider: String,
    val query: JSONObject,         // provider-specific: album, tag, search text…
    val filters: SourceFilters = SourceFilters(),
    val cacheSize: Int = 30,       // images kept on the phone
    val refresh: Refresh = Refresh.DAILY, // NEVER, ON_ADVANCE (when the cache runs low), HOURLY, DAILY
)

data class SourceFilters(
    val orientation: Orientation? = null, // matches the group's screen when null (landscape for DeX, portrait for phone screens)
    val minWidth: Int? = null,
    val minHeight: Int? = null,
    val tagsAll: Set<String> = emptySet(),
    val tagsNone: Set<String> = emptySet(),
    val extra: JSONObject? = null,        // provider-specific (rating, NSFW level…)
)
```

- `Wallpaper.origin: Origin?`: set for images from a source (single or group).
- `Group.source: SourceSpec?`: null for normal groups. Images in a source group are cache entries. Removing one hides that `remoteId` from the group (kept in `Group.excluded`) so a refresh doesn't bring it back.
- Crops for source images are stored by `remoteId`, so a re-downloaded image keeps the crop the user made.

## Provider interface

```kotlin
interface ImageSource {
    val id: String                       // "dreamscape"
    val label: String
    suspend fun isAvailable(c: Context): Boolean
    /** Browse/search for the picker and for filling source groups. */
    suspend fun list(query: JSONObject, filters: SourceFilters, cursor: String?): Page<RemoteImage>
    /** Full-size bytes for one image. */
    suspend fun open(image: RemoteImage): InputStream
    /** Filter and query editors shown in the group screen (optional; a JSON fallback otherwise). */
    @Composable fun QueryEditor(query: JSONObject, onChange: (JSONObject) -> Unit) {}
}

data class RemoteImage(val remoteId: String, val width: Int, val height: Int, val thumbUrl: String?, val origin: Origin)
data class Page<T>(val items: List<T>, val next: String?)
```

Sources register in a `SourceRegistry` (a simple list in the app). Three ways a source can talk to Deskmat, in order of preference:

1. **Content provider from the source's Android app.** If Dreamscape or Sequenzia have an app on the phone, it exports a `ContentProvider` (`content://<app>.deskmat/images?query=…`) protected by a signature or custom permission. No credentials in Deskmat, and works offline against the app's cache.
2. **HTTP API.** Deskmat calls the server directly. Credentials (token or OAuth) are stored with the Android Keystore, never in `library.json`.
3. **Share sheet / intents.** The source app shares images to Deskmat (`ACTION_SEND` / `SEND_MULTIPLE`). This covers single images only and is the cheapest first step.

## Engine behaviour for source groups

- `pick()` only ever chooses from images already on the phone, so applying never waits on the network.
- After each advance, if fewer than `cacheSize / 3` unseen images remain, a refill job is queued (`JobScheduler`, network required, unmetered optional).
- Refills fetch the next page(s), drop images that fail the filters or are excluded, import new ones into the cache, and delete the oldest seen ones over `cacheSize`.
- Offline or provider errors: keep rotating the cached images. The group header shows "Offline, using 24 cached".
- Shuffle picks from the cache; in-order follows the provider's order.

## UI

- **Add images** becomes a menu: *From Gallery* (today) / *From Dreamscape* / *From Sequenzia*. Each source gets a picker with a thumbnail grid and multi-select, and selected images go through the existing crop pager.
- **Group screen** gets a *Source* section: provider, query editor, filters, cache size, refresh, "Refresh now", last refresh status.
- **Image cards** from a source show a small provider badge; the detail page shows the origin metadata and "Open in Dreamscape/Sequenzia".
- **Settings → Sources**: connect/disconnect accounts, data use (Wi-Fi only), storage used by caches.

## Commands and automation

- `dexwp refresh-source <group>` refills a source group now.
- Routines/widgets need no change: they advance the active group whatever fills it.

## Open questions

1. What are Dreamscape and Sequenzia technically: Android apps, web servers, or both? Which API (REST, GraphQL, local files) and which auth?
2. Which filters matter for each (albums, tags, ratings, collections, people, NSFW flags)?
3. Do they already store crop or focus points that Deskmat should use instead of a centred crop?
4. Should source images count towards Deskmat's storage, or stream into a cache that Android may clear?
5. Attribution: should the current image's title/artist show anywhere (status card, widget)?

## Suggested order of work

1. `Origin` on `Wallpaper` + share-sheet import (single images, no API needed).
2. `ImageSource` interface, registry, and picker UI with one provider.
3. Source groups: `SourceSpec`, cache, refill job, offline handling.
4. Second provider, per-source filter editors, Settings → Sources.
