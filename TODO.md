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

**Where:** `TaskKey.kt`, `TaskRegistry.kt`, `StatusScreens.kt`, `SignInScreen.kt`,
`LoadMoreFocusState.kt`, `SidetrackUiState.kt`, `UiSlices.kt`, all eight controllers,
`SidetrackGraph.kt`, `SidetrackLibraryGraph.kt`, `SidetrackPlaybackGraph.kt`,
`SidetrackSettingsGraph.kt`, `PlaybackDriver.kt`

Fourteen pending flags and six error slots (plus `LibraryPage.error`) originally described work in
flight by hand. All of them now come from one registry, the paged ones included. Each controller
still owns its notice policy, and four screens hand-write the same `loading → error → empty →
content` ladder. The inconsistencies were behavioural, not cosmetic:

- `authPending` was set on every sign-in and read by nothing (`AccountController.kt:79`); the
  screen now renders it (see *Sign-in pending state* below).
- `librarySectionsLoading` and `librarySectionsError` were written by the post-sign-in prefetch
  and were unreachable from any screen; both fields are gone.
- Search used to show a text-only `EmptyMessage` where every other first load shows the record;
  it now uses the shared surface.
- A failed load-more permanently dropped the rest of the list (`hasMore = false`) while a failed
  first page offered Try again; the footer now keeps the list and offers the page again.
- Playback has `isPlaying` and `isBuffering` but no "starting": the now-playing UI claims to play
  while `session.play` is still resolving a source (`PlaybackDriver.kt:524-550`), and buffering
  only replaces the elapsed-time label (`Seek.kt:188`).

Consolidate on one task record per unit of work and one renderer per surface kind. The behaviour
fixes ride on that, but stay separate commits so the mechanical moves can land as
no-visual-change reviews.

### One task record

- [x] **`TaskKey` and `TaskRegistry`** — `TaskKey` names each unit of work; the registry owns the
  jobs and is the only writer of `SidetrackUiState.runningTasks`. `start` refuses a second job for
  a running key, `restart` supersedes one without dropping the key (a retry must not flicker
  idle), and `cancel`/`cancelWhere`/`cancelAll` clear it. The owner token means a superseded job's
  `finally` leaves the newer job's record alone; the job is launched `LAZY` so it is registered
  before its block can run on a main-immediate dispatcher.
  Errors are deliberately **not** part of the record: a failed page is retried from its cached
  entry (`LibraryPage.error`, `collectionErrors`, …), which is the lifetime the screen needs, while
  a job is gone once it is cancelled. `startedAt` went away with the slow hint, now owned by
  `LoadSurface`.
- [x] **The single-request domains** — `authPending`, `searchPending`, `searchMorePending`,
  `radioPending`, `artistRadioPending` and `playlistChoicesLoading` are gone; the slice getters in
  `UiSlices.kt` project `runningTasks` back to the fields screens already read. `AccountController`,
  `SearchController`, `RadioController` and `PlaylistController` now start through the registry, so
  their ad-hoc `Job` fields and `isActive` guards are gone too. Covered by `TaskRegistryTest`.
- [x] **Migrate the paged domains** — `Library`/`LibraryMore`, `Collection`/`CollectionMore` and
  `Artist`/`ArtistMore` are the last six keys the registry starts. The six flag fields
  (`libraryPendingPages`, `libraryMorePendingPages`, `pendingCollections`, `collectionMorePending`,
  `pendingArtists`, `artistMorePending`) are gone from `SidetrackUiState`; the `LibraryUiState`
  slice keeps them as projections of `runningTasks`, so no screen changed shape. `QueueController`
  resolves a whole collection under `CollectionMore(key)` — the same work one page at a time — so
  its "still loading — try again" guard is now the registry's dedupe. Sign-out is
  `tasks.cancelAll()` alone: the old flags survived a cancellation whose block never ran, because
  only a store update inside the block cleared them.
- [x] **Radio stops inferring** — the station screens read `RadioUiState.songStation(seed)` and
  `artistStation(id)` instead of `pending || error == null`. The seed (or artist) a request stamps
  before it runs is the attempt's identity, so a stamped request with no station and no error is
  over rather than still working, and the screen settles on the missing state. A station and an
  error built for another artist stay off this artist's screen. Covered by `RadioStationStateTest`.
- [x] **Delete the section-prefetch fields** — the prefetch is a cache warmer whose failure the
  Library's own first page already surfaces. `librarySectionsLoading`, `librarySectionsError` and
  the `catch` that stored them are gone; a failed preview now reports through the notice, including
  the all-failed case that used to be stored silently. Re-add a progress line only if the prefetch
  ever becomes visible work.

### One renderer

- [x] **`MissingCollectionScreen` grows into `LoadSurface`** — `StatusScreens.kt` now owns the
  loading/error/empty switch, and `MissingCollectionScreen`, Library, Search and Add-to-playlist all
  route through it; a null `emptyTitle` keeps Add-to-playlist's transient nothing-yet state blank
  instead of claiming the account has no playlists. Retry stays in the action bar, not in the block.
  The collection route lost its old "Fetching" hint: no other first load carried one.
- [x] **Search first load uses the record** — the query is the title; the category row and the
  `EmptyMessage` loading case are gone. Empty results still keep the plain row, since that is a
  settled state. Covered by `SearchLoadingTest`.
- [x] **Load-more failures keep `hasMore`** — the footer becomes a retry row (`Couldn't load more ·
  Try again`) instead of ending the list. The record for it is `SidetrackUiState.taskFailures`,
  keyed by the same `TaskKey` as the work and written through `TaskRegistry.fail`: a failed first
  page still uses its cached entry, but a failed page has no entry to keep the message, so it lives
  beside the key until `start` retires it. Clearing `hasMore` stays for a catalog that says the
  page is the last one. Retry only on a click, never on scroll, so a dead endpoint cannot loop.
  The artist's two footers share one key, so one failed page marks both; either retry fetches the
  list it belongs to. Covered by `LoadMoreRetryTest` and `PaginationFooterTest`.
- [x] **Artwork screen** — a failed remote fetch now offers Try again in the bar. The retry keeps
  the URL and bumps a reload key, so the fetch effect runs again; a known-missing cover keeps Back
  as the only action because there is nothing to retry.
- [x] **Sign-in pending state** — `authPending` drives `LoadingScreen` with "Waiting for
  <service>…", "Signing in…" or "Waiting for access…" and clears back to the form (values kept)
  when the attempt settles. The action bar stays live because a second press deliberately supersedes
  a stale browser attempt (`AccountController.startSignIn`); disabling it would remove that
  recovery. Covered by `SignInPendingTest`.
- [x] **Slow hint, no artificial delay** — `LoadSurface` swaps its hint to "Still loading…" after
  8 s. Sign-in is the one screen with a more useful hint ("Finish in the browser") and calls
  `LoadingScreen` directly, so it keeps it. Do not delay the record: every first-load branch only
  renders when there is nothing cached, so there is no flash to hide. Do not add a minimum display
  time either; it would slow the common case to flatter the rare one.

### Writes and playback

- [x] **Writes reuse `TaskState`** — `TaskKey.Action(kind, target)` names each write, and
  `TaskRegistry.submit` runs it under that key and waits for the result, so a caller keeps the
  answer it had from the direct call. Create, add, rename and delete go through it: a second press
  while the first is with the service is refused instead of creating a second playlist or appending
  twice. Saves and follows use `restart` instead, because their state has already flipped twice:
  the newer write supersedes the older one and only it decides what the service ends up with.
  `WritesUiState` projects the record for screens: Create reads `CreatePlaylist` and says
  "Creating…", an add-to-playlist row reads its own `AddToPlaylist(key)` and says "Adding…".
  A `SideAction` has no disabled state in the published Sidekit, so the pending label plus the
  registry's refusal is the guard; the row tap returns early too. `ClearCache` is the one kind not
  wired yet, because that item below owns its failure path. Covered by `TaskRegistryTest` and the
  two double-submit cases in `PlaylistControllerTest`.
- [x] **`runOptimistic`** — `SavesController.optimistic` owns the flip → call → rollback + notice
  shape for the track, collection and artist toggles. The lambda renders the value it is given
  rather than coming in two halves, so a rollback cannot drift from the move it undoes; the notice
  wording stays the caller's. `PlaylistController.add` no longer has the shape: it patches the
  count only after the service confirms, so there is nothing to roll back. Covered by
  `SavesControllerTest`.
- [x] **Clear cache gets a failure path** — `SettingsController.clearCache` runs under
  `Action(ClearCache)`: a throwing directory becomes "Couldn't clear the cache" in the bar instead
  of a crash, a second press while it runs is refused, and the size is re-read in `finally` — a
  failed clear may still have removed part of the cache. The Settings row shows "Clearing…" in
  place of the size meanwhile, the same pending language Create and Add use. Covered by
  `ClearCacheFailureTest`.
- [x] **`PlaybackUiState.transport`** — `Transport` is `Idle | Starting | Buffering | Playing |
  Paused | Failed(message)`, stored once in `SidetrackUiState`; `isPlaying` and `isBuffering` are
  read-only projections for screens that have not moved. `TransportState` writes the phase and owns
  the only transition that is not a player event: a 20 s watchdog turns a start that never becomes
  audio into `Failed("Couldn't start playback")`, releasing the clock's hold and saying so in the
  bar. A `Playing` event, a buffering report or the first `Position` tick settles the start;
  `applyQueue(playing = …)` states the queue write's intent without inventing a phase when it has
  none. Covered by `TransportStateTest`.
- [x] **Read the phase everywhere** — the home `NowPlayingTile` says "STARTING" beside the record's
  arc instead of "NOW PLAYING", and "COULDN'T PLAY" for a failed start rather than falling back to
  PAUSED; the song and episode transport rows and `ContextPlayTile` swap their play/pause tile for
  `StartingTile` while a source resolves; `Seek` reads `transport == Buffering` for its label and
  state. The notification needed no change: it publishes `playing` only through `updateState`,
  which is fed by player events, and a start never calls it. The separate `isBuffering` reads are
  gone.

### Semantics and tests

- [x] **Announce the block once** — `StatusScreens.statusBlock` wraps the record, title and hint in
  `clearAndSetSemantics { contentDescription = title; stateDescription = hint }`, and the per-canvas
  descriptions are gone. TalkBack reads one node instead of the record's description followed by
  the same title as text. The block's text is no longer in the semantics tree, so the tests that
  read it by text now read the description and state (`SignInPendingTest`, `SearchLoadingTest`,
  `ArtworkScreenTest`).
- [x] **Loading and errors are live regions** — `LoadingScreen` is always polite, because a first
  load appears after the screen already has focus; `StatusScreen` is polite only for a failure
  (`LoadSurface` passes `announce = true` on its error branch). A settled empty page stays silent:
  it was already on screen, so nothing changed. The footer spinner is untouched, as the list is
  already moving there.
- [x] **Buffering is exposed** — the seek bar's `stateDescription` is "Buffering" while it waits,
  matching the visible label, instead of claiming a position nobody can vouch for. Covered by
  `AccessibilitySemanticsTest`.
- [ ] **Tests** — `AccessibilitySemanticsTest` now covers one announcement per status block, the
  live-region split and the buffering state, and `PlaylistControllerTest` covers the refused second
  Create/Add. Still missing: `ArtworkScreenTest` for the retry action (it needs a failing remote
  fetch), a controller test for sign-in pending, and confirming `PaginationFooterTest` has a failed
  load-more case distinct from `LoadMoreRetryTest`. `LibraryPageCacheTest` and `SearchPageCacheTest`
  still pass through the state move — they are the safety net for the collapse.
- [x] **Screenshots** — the sign-in pending baseline already exists from the sign-in commit
  (`SidetrackScreenshotTest.signInPending`, `app/screenshots/sidetrack_sign_in_pending.png`). The
  semantics move changes no pixels, so no baseline moved.
- [x] **CPD** — re-ran after the collapse: 32 Kotlin groups and 1 Rust group, 668 lines of a 668
  budget. The `UiSlices` ↔ `SidetrackUiState` clone the item expected to shrink is already gone (it
  was cleaned with the field collapse), so this move adds nothing to lower; the next drop needs one
  of the blocks in the duplication section, not a budget tweak.

### Decisions

- No skeletons for lists. The record and the footer spinner are the two loading languages; a third
  would need its own baselines and would still not say more than "working".
- Per-row artwork keeps its silent placeholder. `RemoteArtworkImage` cannot report failure, and
  replacing it row-by-row with the manual loader costs a bitmap state per row. Revisit if Sidekit
  exposes an `onState` callback; the artwork screen is fixed independently.
- No global connectivity banner. Catalog failures already become page errors with retry or a
  notice with the provider's message; a banner would duplicate both.

Two more slices have landed: the paged domains moved onto the registry, and the load-more retry
that migration made possible. Land the rest in this order, each shippable on its own: writes and
`transport`, then semantics and tests.

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
- [x] **UI state slices** — the clone is gone from the report: the six paging flags no longer exist
  in `SidetrackUiState` to be re-listed, and `LibraryUiState` now projects them from the registry.
  Budget lowered from 676 to 668, which also absorbs the paging fakes the two list tests now share
  as `PagingCatalog`.
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
