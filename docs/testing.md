# Testing

Sidetrack's unit tests run on the JVM through Robolectric and Roborazzi. The suites are split by
cost and by what they need, so the default loop stays fast and hermetic.

## Running the suites

| Command | What runs | Needs |
|---|---|---|
| `./gradlew testDebugUnitTest` | the default gate: every unit test except screenshots and live-server integration | nothing |
| `./gradlew coverage` | the default gate plus every coverage floor | nothing |
| `./gradlew testDebugUnitTest -PsidetrackScreenshots` | adds the Roborazzi baseline **verifications** | nothing |
| `./gradlew testDebugUnitTest -PsidetrackScreenshotsRecord` | rewrites the baseline PNGs instead of verifying | nothing |
| `./gradlew testDebugUnitTest -PsidetrackIntegration` | adds the live-server tests (`*IntegrationTest`) | reachable Navidrome/Jellyfin/Plex |

Screenshot tests verify by default. Recording is opt-in (`-PsidetrackScreenshotsRecord`): a suite
that records on every run can never fail.

`*IntegrationTest` classes are excluded from the default run and only execute with
`-PsidetrackIntegration`. Each one skips itself when its env vars are absent, so they can also be
run by name: see [jellyfin-integration.md](jellyfin-integration.md) and
[plex-integration.md](plex-integration.md) for the variables.

## Where tests live

| Module | Scope |
|---|---|
| `:core` | service-agnostic models, persistence, queue/history/progress repositories, playback controller, sleep timer, drain policy |
| `:app` | Compose screens, view model, navigation, service glue |
| `:provider-spotify` | Spotify auth, Web API mapping, native catalog/gateway, librespot engine and the controller it builds |
| `:provider-local` | Device catalog grouping, search, paging, `.m3u8` playlists, liked list |
| `:provider-subsonic` | Subsonic auth, API mapping, family entries |
| `:provider-jellyfin` | Jellyfin auth, API mapping, catalog, playback reporting |
| `:provider-plex` | Plex PIN auth, API mapping, catalog, playback reporting |

Pure logic lives in `:core` and is tested there: no Compose rule, no Robolectric where avoidable,
so those tests run in seconds instead of minutes.

## Coverage

Coverage is measured with [Kover](https://kotlin.github.io/kotlinx-kover/gradle-plugin/) on the
`debug` variant. `:app` is the merging module, so `:app:koverVerifyDebug` measures **all** modules
together; the other modules verify their own tests against their own code.

```bash
./gradlew coverage                          # fast-suite gate
./gradlew coverage -PsidetrackScreenshots   # full-suite gate, higher floors
```

Reports land in `app/build/reports/kover/` (`report.xml`, `html/index.html`) and per module under
`<module>/build/reports/kover/`.

Floors are a **ratchet**: the values in the build files are the levels measured when Kover was
introduced, and they only ever move up. The product floor depends on the suite that ran because
the screenshot tests carry a large share of UI coverage.

| Gate | Line | Branch |
|---|---:|---:|
| `:app` product, default | 62 | 42 |
| `:app` product, full (`-PsidetrackScreenshots`) | 71 | 45 |
| `:core` | 63 | 51 |
| `:provider-spotify` | 77 | 61 |
| `:provider-jellyfin` | 92 | 62 |
| `:provider-local` | 61 | 43 |
| `:provider-subsonic` | 70 | 42 |
| `:provider-plex` | 64 | 36 |

The floor is the gate, not the target. Re-measure after a suite change and raise the floor; never
lower one to absorb a regression.

## Capturing playback logs from a device

A playback bug reported from the phone needs `SidetrackDebug`'s rolling file, because logd keeps
only minutes of this app's lines once the Spotify bridge starts writing PCM counters. The file is
`logs/sidetrack.log` under the app's external files directory, capped at 1.5 MB with two backups:
an hour or two of playback, so pull it before the next repro rather than after the next week.

| Build | File log |
|---|---|
| `debug`, `dev` | on unconditionally — `installFileLog(always = true)` |
| `release`, `profile`, `demo` | off until the log tag is set: `adb shell setprop log.tag.SidetrackDebug DEBUG` |

`log.tag.*` is not a persisted property, so a release build stops logging after a reboot and the
capture silently produces nothing. Set the tag again, or capture on `dev`.

```bash
adb -s <serial> shell setprop log.tag.SidetrackDebug DEBUG   # release/profile/demo only
# … reproduce …
adb -s <serial> pull /sdcard/Android/data/<pkg>/files/logs/
```

A transport command logs its origin (`session command:`, `noisy:`, `transport toggle:`) and a
command the player never honoured logs `command unconfirmed after <n>ms`. A pause in the log without
one of those lines came from somewhere the app was not told about: check `dumpsys audio` for the
focus commands and `A2DP` device log, which survive longer than logcat here.

## Adding a test

- Pure logic goes in `:core` — it is the cheapest place to run it.
- A test that needs `ApplicationProvider`, `Context` or Compose belongs in the module that owns the
  code, not `:app`, unless it exercises the app's own screens or view model.
- A test that needs a live server must be named `*IntegrationTest` and guard itself with
  `Assume`.
- A test that captures pixels must be named `*ScreenshotTest`, so it lands in the opt-in suite.
- Prefer a provider module test over an `:app` test for provider-owned behaviour; `:app` should
  only cover its own glue.
