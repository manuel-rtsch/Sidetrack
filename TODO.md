# TODO

## Per-track loudness and gapless handoff for the device library

**Where:** `LocalCatalog`, `Media3ServiceRuntime`, `:core` playback

`ServiceCapabilities.Device` leaves normalisation and gapless off because the shared Media3 path
delivers neither: `Media3ServiceRuntime.setGapless` only persists the preference, and there is no
ReplayGain stage at all. The device library is the place where both are finally worth doing — the
files are local, so a ReplayGain tag reader can read the gain before the track starts and an
`AudioProcessor` can apply it without a reload, and `setPauseAtEndOfMediaItems(false)` plus a
pre-buffered next item gives the seamless handoff that only librespot has today. Turning the flags
on before that work lands would put two controls in Settings that do nothing.

## Apply audio settings without rebuilding the player

**Where:** `SpotifyPlayback.reconfigure()`, `native/sidetrack-spotify/src/engine.rs`

Toggling Normalize volume or Gapless playback needs a new librespot `Player`: `PlayerConfig` is
consumed by `Player::new` and stored in `PlayerInternal`, and the only runtime config mutation the
0.8.0 API exposes is `set_auto_normalise_as_album`. Sidetrack therefore rebuilds the player, which
releases the AudioTrack and fetches the current item again.

That rebuild is now deferred to a silent moment (the next start that resets the sink, or the next
pause), so it no longer cuts into a playing track. What remains is that a toggle does not take
effect on the current track until then.

**Option B:** vendor `librespot-playback` (the repo already vendors `librespot-core` through
`[patch.crates-io]`) and add runtime setters:

- `PlayerCommand::SetNormalisation(bool)` and `SetGapless(bool)` that assign `self.config.*` in the
  player loop, plus `Player::set_normalisation` and `Player::set_gapless`.
- `gapless` is read only in `handle_command_load`, so flipping the flag is enough.
- `normalisation` is read per audio packet, but the gain factor is computed at load from the
  track's normalisation data, so a mid-track change steps the gain from the next packet — no
  silence, no reload.

`SpotifySession.applyAudioSettings()` would then call the setters instead of
`playback.reconfigure()`, and the deferral in `SpotifyPlayback` could go away. The tests in
`SpotifyPlaybackTest` that cover the deferral would move to the gateway.

**Cost:** a second vendored crate to keep in step with librespot upgrades.

## Finish the test-suite rebalance

**Where:** `:app` Spotify tests, `SidetrackScreenshotTest`, shared fixtures

- Delete or move the `:app` Spotify tests that duplicate `:provider-spotify` behaviour
  (`SpotifyViewModelEventTest`, `SpotifyFallbackStateTest`, `SpotifyPersistenceTest`,
  `SpotifyPaginationTest`). Keep the view-model-only assertions; re-measure afterwards.
- Cap the screenshot suite: keep one baseline per screen state that semantics tests cannot
  express, drop variants that only re-render a covered state.
- Move shared fixtures (`MediaKeyFixtures`, `FakeHttpStream`, `RecordingServiceRuntime`,
  `Previews`, Spotify fakes) into an Android `testFixtures` source set on `:core`, so the
  remaining `:app` playback tests can move.
- Re-baseline the coverage floors once after the deletions, then raise them again. Do not lower a
  floor to absorb duplicate tests.

## Promo recording chain

**Where:** `maestro/flows/`, `maestro/README.md`

- Add `flows/utility/seed-fixture.yaml` (play N distinct fixture tracks, add two to the queue,
  return Home) so the account-mode recording chain does not depend on leftover state.
- `flows/utility/record-prepare.yaml` pins TOTO's *Africa*, so it cannot be reused with neutral
  content.
- Promo takes 02–04 still need the pacing work already applied to 01 (dead guards and long scroll
  hunts) to stay inside the 40 s clip.

## Loading and status surfaces

**Where:** `StatusScreens.kt`, `SignInScreen.kt`, `LoadMoreFocusState.kt`, `SidetrackUiState.kt`,
`UiSlices.kt`, all eight controllers, `SidetrackGraph.kt`, `SidetrackLibraryGraph.kt`,
`SidetrackPlaybackGraph.kt`, `SidetrackSettingsGraph.kt`, `PlaybackDriver.kt`

Thirteen pending flags and six error slots (plus `LibraryPage.error`) describe work in flight.
Each controller owns its own job map and its own notice policy, and four screens hand-write the
same `loading → error → empty → content` ladder. The inconsistencies are behavioural, not
cosmetic:

- `authPending` was set on every sign-in and read by nothing (`AccountController.kt:79`); the
  screen now renders it (see *Sign-in pending state* below).
- `librarySectionsLoading` and `librarySectionsError` were written by the post-sign-in prefetch
  and were unreachable from any screen; both fields are gone.
- Search shows a text-only `EmptyMessage` where every other first load shows the record
  (`SearchScreen.kt:488-497`), and that row is not announced.
- A failed load-more permanently drops the rest of the list (`hasMore = false`,
  `LibraryController.kt:574-611` and `:120-148`) while a failed first page offers Try again.
- Playback has `isPlaying` and `isBuffering` but no "starting": the now-playing UI claims to play
  while `session.play` is still resolving a source (`PlaybackDriver.kt:524-550`), and buffering
  only replaces the elapsed-time label (`Seek.kt:188`).

Consolidate on one task record per unit of work and one renderer per surface kind. The behaviour
fixes ride on that, but stay separate commits so the mechanical moves can land as
no-visual-change reviews.

### One task record

- [ ] **`TaskState`** — absence means idle:
  ```kotlin
  sealed interface TaskState {
      data class Running(val startedAt: Long) : TaskState
      data class Failed(val message: String) : TaskState
  }
  ```
  `TaskKey` has one case per unit of work: `Library(filter)`, `LibraryMore(filter)`,
  `Collection(key)`, `CollectionMore(key)`, `Artist(id)`, `ArtistMore(id)`, `Search(query, scope)`,
  `SearchMore(query, scope)`, `PlaylistChoices`, `Radio(seed)`, `ArtistRadio(id)`, `SignIn`, and
  one `Action` case per write. `SidetrackUiState.tasks: Map<TaskKey, TaskState>` replaces the
  flag/error pairs; the content caches stay where they are.
- [ ] **`TaskRegistry`** — one Job map keyed by `TaskKey` plus `start(key) {}` that returns the
  running Job instead of launching a second, `fail(key, e, hasContent)` that decides between page
  error and notice in one place, and `cancelAll()` on sign-out. Owned by `SidetrackViewModel` and
  handed to the controllers, so `libraryMoreJobs` and the ad-hoc `isActive` checks disappear.
- [ ] **Collapse the pairs** — `searchPending`+`searchError` → `Search`; `searchMorePending` →
  `SearchMore`; `libraryPendingPages`+`LibraryPage.error` → `Library`; `libraryMorePendingPages` →
  `LibraryMore`; `pendingCollections`+`collectionErrors` → `Collection`; `collectionMorePending` →
  `CollectionMore`; `pendingArtists`+`artistErrors` → `Artist`; `artistMorePending` →
  `ArtistMore`; `playlistChoicesLoading`+`playlistChoicesError` → `PlaylistChoices`;
  `radioPending`+`radioError` → `Radio`; `artistRadioPending`+`artistRadioError` → `ArtistRadio`;
  `authPending` → `SignIn`.
- [ ] **Keep the derived properties** (`libraryLoading`, `libraryMorePending`) as projections over
  `tasks` for one release, so surfaces migrate one at a time. Migrate Library, then collection and
  artist, then search, then playlist choices and radio.
- [ ] **Radio stops inferring** — `loading = pending || error == null` (`SidetrackPlaybackGraph.kt`)
  becomes `Running` or `(content == null && !Failed)`; a request that ends without a result can no
  longer spin forever.
- [x] **Delete the section-prefetch fields** — the prefetch is a cache warmer whose failure the
  Library's own first page already surfaces. `librarySectionsLoading`, `librarySectionsError` and
  the `catch` that stored them are gone; a failed preview now reports through the notice, including
  the all-failed case that used to be stored silently. Re-add a progress line only if the prefetch
  ever becomes visible work.

### One renderer

- [ ] **`MissingCollectionScreen` grows into `LoadSurface`** — it already takes
  `loading`/`loadingTitle`/`error`/`onRetry` and is the only place that renders the full-page
  record with a retry action. Add `empty` content and use it for Library, Search and
  Add-to-playlist too (the two radio routes already go through it), deleting their four copies of
  the `when` ladder. Retry stays in the action bar, not in the block: that is the existing contract
  (`StatusScreens.kt:124-127`).
- [ ] **Search first load uses the record** — `LoadSurface(loading = searchPending && results empty)`
  with the query in the hint; drop the `EmptyMessage` loading case. Empty results keep the plain
  row, since that is a settled state.
- [ ] **Load-more failures keep `hasMore`** — the footer becomes a retry row (`Couldn't load more ·
  Try again`) instead of ending the list. Clear `hasMore` only when the catalog says so, and only
  retry on a click, never on scroll, so a dead endpoint cannot loop.
- [ ] **Artwork screen** — add the retry action to the bar alongside Back. The manual loader already
  reports failure (`SidetrackArtworkScreen.kt:124-175`); it just has nowhere to go today.
- [x] **Sign-in pending state** — `authPending` drives `LoadingScreen` with "Waiting for
  <service>…", "Signing in…" or "Waiting for access…" and clears back to the form (values kept)
  when the attempt settles. The action bar stays live because a second press deliberately supersedes
  a stale browser attempt (`AccountController.startSignIn`); disabling it would remove that
  recovery. Covered by `SignInPendingTest`.
- [ ] **Slow hint, no artificial delay** — one helper swaps the hint to "Still loading…" after 8 s.
  Do not delay the record: every first-load branch only renders when there is nothing cached, so
  there is no flash to hide. Do not add a minimum display time either; it would slow the
  common case to flatter the rare one.

### Writes and playback

- [ ] **Writes reuse `TaskState`** — `TaskKey.Action(CreatePlaylist | AddToPlaylist(playlist) |
  RenamePlaylist | DeletePlaylist | ClearCache | ToggleSave(id) | Follow(id))`. Buttons read the
  running key and disable, which also closes the double-submit hole in Create playlist and the
  duplicate `add` (both currently launch unconditionally).
- [ ] **`runOptimistic`** — the flip → call → rollback + notice shape exists three times in
  `SavesController` and again in `PlaylistController.add`; one helper owns it. Notice wording stays
  the callers'.
- [ ] **Clear cache gets a failure path** — `SettingsController.clearCache` currently launches
  without a `catch`; route it through the registry so a throwing clear becomes a notice instead of
  a crash, and refresh the size when it finishes.
- [ ] **`PlaybackUiState.transport`** — `Idle | Starting | Buffering | Playing | Paused | Failed`,
  derived from the same player events plus the in-flight `startRemote`, with `isPlaying` and
  `isBuffering` kept as read-only projections until readers migrate. `beginPlayback`/`startRemote`
  set `Starting`; the first progress or playing event settles it; a 20 s watchdog turns a stuck
  start into `Failed("Couldn't start playback")`.
- [ ] **Read the phase everywhere** — `NowPlayingTile` stops showing play while `Starting` and
  shows the arc instead; `Seek.kt` shows `Buffering…` from the same value; the notification does
  not publish a playing state before audio exists. Delete the separate `isBuffering` reads.

### Semantics and tests

- [ ] **Announce the block once** — the record carries `contentDescription = title` while the same
  title is drawn as text (`StatusScreens.kt:104`, `:170`), so TalkBack can read it twice. Wrap the
  block in `clearAndSetSemantics { contentDescription = title; stateDescription = hint }` and drop
  the per-canvas description.
- [ ] **Loading and errors are live regions** — `liveRegion = Polite` on the first-load and error
  blocks, so a request that starts or fails after focus landed is announced. Do not set it on the
  footer spinner; the list is already moving there.
- [ ] **Buffering is exposed** — `stateDescription` on the seek row, not just the visible label.
- [ ] **Tests** — extend `AccessibilitySemanticsTest` (one announcement per status block, buffering
  state description); `PaginationFooterTest` (failed load-more retries without losing rows);
  `ArtworkScreenTest` (retry action); a controller test for sign-in pending and for the second
  Create/Add being refused while one is running. `LibraryPageCacheTest` and `SearchPageCacheTest`
  must keep passing through the state move — they are the safety net for the collapse.
- [ ] **Screenshots** — `SidetrackScreenshotTest:449` is the only `LoadingScreen` baseline; add one
  for the sign-in pending state. The record does not move, so no other baseline changes.
- [ ] **CPD** — re-run `./gradlew cpdCheck` after the ladders collapse; the `UiSlices` ↔
  `SidetrackUiState` clone listed below should shrink with the field collapse, and the budget can
  follow the measurement rather than lead it.

### Decisions

- No skeletons for lists. The record and the footer spinner are the two loading languages; a third
  would need its own baselines and would still not say more than "working".
- Per-row artwork keeps its silent placeholder. `RemoteArtworkImage` cannot report failure, and
  replacing it row-by-row with the manual loader costs a bitmap state per row. Revisit if Sidekit
  exposes an `onState` callback; the artwork screen is fixed independently.
- No global connectivity banner. Catalog failures already become page errors with retry or a
  notice with the provider's message; a banner would duplicate both.

The first slice has landed (sign-in pending and the dead section fields). Land the rest in this
order, each shippable on its own: `LoadSurface` over the existing flags, then `TaskState`, then
writes and `transport`, then semantics and tests.

## Code duplication

**Where:** the whole repo, measured by `./gradlew cpdCheck`

PMD CPD runs over the Kotlin and Rust sources at 80 tokens with `--ignore-identifiers
--ignore-literals`, so every hit below is a structural clone rather than two functions that happen
to share a shape. `./gradlew cpd` writes the full blocks to `build/reports/cpd/`; `cpdCheck` fails
when the total passes `sidetrack.cpd.maxDuplicateLines` in `gradle.properties` (676 today). Lower
that number in the same commit that lands one of these, or the budget will not stop the next one.

The list is ordered by what the duplication risks, not by its size: duplicated retry logic is a bug
waiting to happen, duplicated test setup is mostly a patience tax.

### Production

- [x] **`catalog.rs` playlist-change retry scaffold** — one `playlist_change` helper now owns the
  read → build → post → retry-on-`is_revision_conflict` shape for rename, add and remove, with
  `ensure_editable` holding the capability check the two item paths shared. The protobuf readers in
  the same file share `read_bytes`/`skip_field`, so the collection parsers no longer repeat the
  field-skipping arms. Budget lowered from 800 to 740. Covered by
  `a_collection_page_skips_unknown_fields` and `a_truncated_collection_page_stops_at_the_bad_length`.
- [x] **Playlist mutations across the providers** — `ClientPlaylistWriter` now supplies the
  `MediaKey`/`MediaItem` quartet from a client's `PlaylistTransport`, which `JellyfinApi` and
  `SubsonicApi` implement, so those two catalogs hold one property instead of four copies of the
  same delegation. Plex and the device library keep `MediaLibraryWriter` directly: Plex names
  playlist entries rather than tracks, and the device library writes local files. The second cited
  pair was not the quartet but the `Collection` mapping of a playlist, now shared as
  `playlistCollection` by the four catalogs that mapped it field for field (Spotify keeps its
  own: its playlist subtitle carries the track count). Budget lowered from 740 to 700.
- [x] **DI wiring** — `sidetrackArtistRoute` now decodes the `{key}` argument, opens the artist and
  collects the four slices both artist routes read, and `MissingArtistScreen` holds the loading /
  error / missing fallback they were both spelling out; `RadioCollectionScreen` holds the collection
  body the song and artist stations share, which leaves each radio route with only its own play and
  artwork actions. Budget lowered from 700 to 676.
- [ ] **HTTP request scaffold** — 93 at `JellyfinAuth.kt:55` ↔ `PlexApi.kt:338`, and 81 at
  `PlexApi.kt:339` ↔ `SubsonicApi.kt:47` plus `JellyfinAuth.kt:56`.
- [ ] **UI state slices** — 88 tokens at `SidetrackUiState.kt:43` ↔ `UiSlices.kt:41`. The field
  collapse in *Loading and status surfaces* should shrink this; re-measure before lowering.
- [ ] **`lib.rs:512` ↔ `lib.rs:530`** — 112 tokens in the JNI entry points.
- [x] **`EpisodeMeta`/`EpisodeProgress` file envelope** — the magic/version/count frame, the atomic
  replace and the version-1 service fallback now live in `EpisodeFileFrame`; only the record body
  differs. Budget lowered from 824 to 800. Covered by `EpisodeMetaStoreTest`.

### Test and demo

- [ ] **`FictionalPack.kt` ↔ `RealPack.kt`** — 2057 tokens in three blocks, the largest single
  finding in the repo. Two demo catalogues sharing their whole construction; parameterise one
  builder over two data sets. Both also duplicate five blocks against `MockSpotifyDataSource.kt`
  (up to 251 tokens).
- [ ] **Compose library test setup** — `ArtistLibraryGutterTest` /
  `LibraryFilterRowTest` / `LibraryCategorySwitchFocusTest` share ~150 tokens at `:36`; same shape
  for `SearchCategorySwitchFocusTest` ↔ `SearchFilterRowTest` (100) and
  `SearchFieldLabelTest` ↔ `SearchFieldStateDescriptionTest` (97).
- [ ] **Screenshot harness** — `SidetrackScreenshotTest` ↔ `StoreScreenshotTest`, 229 tokens over
  two blocks.
- [ ] **Spotify native test fakes** — `NativeSpotifyGatewayTest` ↔ `SpotifyAudioSinkTest` ↔
  `SpotifySessionNativeTest`, four blocks between 106 and 193 tokens.
- [ ] **`RecordingServiceRuntime` ↔ `ServiceRuntimeExtensionsTest`** — 191 tokens over two blocks.
- [ ] Remaining single-block pairs under 150 tokens (playlist controller tests, PlaybackReporter
  tests, `NativeSpotifyCatalogTest`, `SubsonicApiTest`, `SpotifyFallbackStateTest` and the rest).
