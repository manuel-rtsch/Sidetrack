# Architecture

Sidetrack is a multi-module Gradle build. Each service is a provider module behind a shared
domain model; the app is the only place that knows every provider.

```
:app                  UI, navigation, view model, picker, session registry
 ├── :core             domain model, provider contracts, shared repositories, playback engines
 ├── :provider-spotify   ─┐
 ├── :provider-subsonic ─┤
 ├── :provider-jellyfin ─┼─► :core   (never the reverse)
 ├── :provider-plex     ─┤
 └── :provider-local   ─┘
```

`:provider-local` is the device's own library: MediaStore for the catalog and playback URIs,
`.m3u8` files it owns for the playlists it writes. Its sign-in is a storage permission, not an
account, so `LocalAuth` stores nothing and `SignInKind.Device` gives the sign-in screen a mode with
no fields.

## Boundaries

- `:core` may name a provider only through `ServiceId` constants and the `ServiceCapabilities`
  sets. It must not reference provider code.
- A provider module depends only on `:core` and its own third-party libraries.
- Only `:app` knows every provider: `KnownServices`, the `AppSession.build` registry, and the
  demo build's content source.
- Package names are unchanged across modules; a module is a build boundary, not a rename. The
  native Spotify bridge (`native/sidetrack-spotify`) is packaged by `:app` and never moves.

Tests live with their subject. A test that needs both a provider and `KnownServices` belongs in
`:app`; see [testing.md](testing.md) for the suite split.

## Contracts

- `data/MusicService.kt` — `MediaCatalog`, `MediaLibraryWriter`, `ServiceAuth`,
  `MusicServiceProvider`, `AuthInput`, `ServiceAccount`, `ServiceFailure`.
- `data/ServiceRuntime.kt` — the single contract the UI sees, plus optional mix-ins
  (`NativePlaybackRuntime`, `QualitySettingsRuntime`, `CacheRuntime`) reached through `as?`
  extensions, so a provider without a feature stubs nothing.
- `MediaModels.kt` — `ServiceId`, `MediaKey`, `ServiceCapabilities`. The UI gates categories and
  search scopes on capability sets (`availableLibraryFilters`, `availableSearchScopes`), never
  on a provider id.
- Shared playback — `playback/PlaybackController.kt`, `Media3PlaybackEngine.kt` and
  `ExoPlayerMedia3Player.kt` serve every non-Spotify service; Spotify adds its librespot engine
  in `:provider-spotify`.

## Persistence

Every provider gets its own names for the encrypted credential store and for the queue, history,
resume and settings files, and the Subsonic family derives them from the entry's `ServiceId` (see
`SubsonicFamily`). Two accounts on one device therefore never mix.

## Adding a provider

1. Capability set and `ServiceId` in `:core` (`MediaModels.kt`).
2. A provider module implementing the contracts, with its own encrypted store.
3. Registration in `:app` (`KnownServices`, `AppSession.build`, sign-in kind).
4. Capability rows, library filters and search scopes follow from the capability set; the UI
   needs no per-provider branch.

`docs/provider-capabilities.md` records what each provider implements; the per-provider
integration notes record the non-obvious wire decisions.
