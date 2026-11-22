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
