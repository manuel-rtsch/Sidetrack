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
| `:provider-subsonic` | 70 | 42 |
| `:provider-plex` | 64 | 36 |

The floor is the gate, not the target. Re-measure after a suite change and raise the floor; never
lower one to absorb a regression.

## Adding a test

- Pure logic goes in `:core` — it is the cheapest place to run it.
- A test that needs `ApplicationProvider`, `Context` or Compose belongs in the module that owns the
  code, not `:app`, unless it exercises the app's own screens or view model.
- A test that needs a live server must be named `*IntegrationTest` and guard itself with
  `Assume`.
- A test that captures pixels must be named `*ScreenshotTest`, so it lands in the opt-in suite.
- Prefer a provider module test over an `:app` test for provider-owned behaviour; `:app` should
  only cover its own glue.
