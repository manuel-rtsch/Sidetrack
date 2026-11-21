# Maestro

Reproducible UI flows for Sidetrack, run against a connected device or emulator. Maestro is
installed at `~/.maestro/bin/maestro`; add it to the `PATH` first.

```sh
export PATH="$PATH:$HOME/.maestro/bin"
cd maestro
maestro test .                                   # the suite in config.yaml order
maestro test flows/suite/01-browse-play.yaml     # a single flow
```

`./run-suite.sh` runs the whole suite with device animations off, a different accent colour before
each flow (Lime, Orange, Blue, Pink by default) and a per-flow timing report:

```sh
./run-suite.sh
FLOW_MAX_SECONDS=45 ./run-suite.sh                        # fail when a flow exceeds the limit
SIDETRACK_ACCENTS="Lime Orange Pink" ./run-suite.sh        # override the rotation
```

Suite artifacts land in `build/maestro-suite/`, accent steps and their logs in
`build/maestro-accent/`, so the timing report only covers the flows. Re-run the report alone with
`./scripts/flow-times.py build/maestro-suite --verbose`.

## Recordings

The checked-in recordings come from the recording-first variants in `flows/promo/`:

| Recording | Flow | Accent |
| --- | --- | --- |
| `recordings/01-browse-play.mp4` | `flows/promo/01-browse-play.yaml` | Lime |
| `recordings/02-search-library-writes.mp4` | `flows/promo/02-search-library-writes.yaml` | Orange |
| `recordings/03-queue-transport.mp4` | `flows/promo/03-queue-transport.yaml` | Blue |
| `recordings/04-history-settings.mp4` | `flows/promo/04-history-settings.yaml` | Pink |

The promo variants are recording-first: warm launch, no guards that cannot match, settle and
scroll overrides, and a narrative that fits a 40-second clip. Each take tells a different story:

| Take | Owns |
| --- | --- |
| 01 Browse & Play | Home hero, recent rows, the Library tour, album pages, playback from three places |
| 02 Search & Library Writes | Search, Follow, artist pages, shows, episodes |
| 03 Queue & Transport | Queue editing, swipe actions, transport, sleep timer |
| 04 History & Settings | History, playback settings, audio quality, Appearance, Keyboard, cache/history |

The suite flows in `flows/` stay authoritative for coverage: they keep guards, both-direction
toggles and assertions the promo takes drop. Record with:

```sh
SIDETRACK_ACCENT=Lime ./record.sh flows/promo/01-browse-play.yaml "recordings/01-browse-play.mp4"
```

`record.sh` detects the demo build by the `-demo` version name and runs
`flows/utility/record-prepare-demo.yaml`; otherwise it runs `flows/utility/record-prepare.yaml`.
It turns animations off for the take (restore with `SIDETRACK_KEEP_ANIMATIONS=1`), picks the first
online device (`SIDETRACK_DEVICE=<serial>` overrides), and starts the recording when the flow's
`launchApp` completes (`SIDETRACK_RECORD_START_MARKER` overrides the marker). `maestro record` also
works but is slow and leaves an unplayable file if interrupted.

### Emulator

`SIDETRACK_DEVICE=emulator-5554` records against an emulator, which is enough for flows that do
not type: 480×640 matches the Sidephone layouts. Timing runs ~1.3× faster than the phone, so a
phone take would overrun the clip if the flow is paced for the emulator. `record.sh` hides the
soft keyboard for the take (`SIDETRACK_KEEP_IME=1` opts out), and uses the standalone accent flow
because the emulator has no Sidekit host.

## Demo build

The recordings are made against a `demo` build that serves a fixed catalog instead of the
signed-in account's library, so videos never show personal data and search results never change
with Spotify's ranking. Auth and playback stay real; the recordings are muted. The default pack
shows real, widely known songs with bundled official covers; `-PdemoPack=fictional` builds the
fully invented pack. See [CONTENT-FIXTURE.md](CONTENT-FIXTURE.md).

```sh
./gradlew :app:assembleDemo
adb install -r app/build/outputs/apk/demo/app-demo.apk   # replaces the release app
```

Every take needs its own cold launch: the demo build rewrites history and the queue at process
start, so each video starts from the same rows. Never set `SIDETRACK_SKIP_PREPARE=1` with the demo
build. Install the release APK to go back.

## Accent colour between runs

Sidetrack is host-backed when Sidekit is installed: it reads the case colour from Sidekit and
ignores its own Appearance screen. Change the host setting, then restart Sidetrack:

```sh
maestro test flows/utility/set-accent.yaml -e ACCENT=Lime
```

It starts and ends on Sidetrack Home and force-stops both apps, so run it between flows rather
than inside one.

## Device fixture

The suite assumes a seeded device left on **Home**: signed in; one current **track**; at least
**6 distinct** recently played entries, so Home shows **See more history**; at least **2 upcoming**
queue items.

To seed manually, play six different tracks once each (history deduplicates by item id), add two
recent tracks to the queue, and return Home. Do not **Clear history** afterwards. A dedicated
`flows/utility/seed-fixture.yaml` is still a TODO; the flows must not depend on one another beyond
this fixture. The demo build reseeds itself and needs none of this.

The full inventory of what the flows consume — counts, ordering, durations, T9 constraints and the
demo packs — is in [CONTENT-FIXTURE.md](CONTENT-FIXTURE.md).

## Search input

On the Sidephone keypad, Maestro `inputText` arrives as individual key events. The T9 engine only
multi-taps digits in **Abc** mode, so the Search flow taps the field, presses a harmless key so the
keypad bar appears, leaves **123** if needed, erases the probe (`eraseText: 2`) and enters the
query as T9 digit groups. The pinned query is **bowie**, entered as `22666944433`; the flow taps
the first result below the **SHOWS** heading and never asserts its title.

## Flows

Only `flows/suite/` is listed in `config.yaml`; `flows/demo/` and `flows/utility/` are outside the
automated suite.

- `suite/01-browse-play.yaml` — Home → Library → followed artist → top track → album → Now
  Playing → Home. Covers track, artist and album media, and adds a history entry for `04`.
- `suite/02-search-library-writes.yaml` — Home → Search → `Search in` scopes → follow artist →
  save album → show episode playback → Now Playing.
- `suite/03-queue-transport.yaml` — queue two recent tracks, remove/Undo, Clear/Undo, optional
  Shuffle, Now Playing transport (play/pause, Next, repeat, Save, seek).
- `suite/04-history-settings.yaml` — History removal → Settings (Autoplay, Normalize volume,
  Gapless, Audio quality) → cancel Clear cache and Clear history.
- `promo/01…04` — recording-first variants of the four takes above.
- `demo/*` — short manual flows for the demo build.
- `utility/record-prepare.yaml` — recording setup: clear history, start a known track.
- `utility/record-prepare-demo.yaml` — cold launch only; the demo build reseeds itself.
- `utility/set-accent.yaml` — sets Sidekit's host case colour (`Lime`, `Orange`, `Blue`, `Pink`,
  `Sage`, `Lavender`) and restarts Sidetrack.

## Promo videos

The recordings are composited into 4:9 panels and a 4-up 16:9 grid in `design/promo/`; rebuild with
`design/promo/build-videos.sh`. Geometry, crop values and ffmpeg commands are in
[PROMO-VIDEO.md](PROMO-VIDEO.md).
