# Demo content fixture

The suite and promo flows need a known library, history and queue. In account mode that means a
seeded personal profile; the checked-in recordings use the `demo` build instead, which serves a
fixed catalog. This document is the inventory of what the flows consume and what the demo build
provides.

## What the flows need

| # | Resource | Minimum | Consumed by | Constraint |
| --- | --- | --- | --- | --- |
| 1 | Signed-in account | 1 | every flow | online; real playback needs Spotify Premium |
| 2 | Liked songs | 1 | `01`, `03` | `01` anchors its scroll reset on the **LIKED SONGS** heading, which is omitted when empty |
| 3 | Followed artists | 2 | `01` | every followed artist needs ≥ 3 top tracks and ≥ 1 album with ≥ 1 track, because order is Spotify's `added_at` |
| 4 | Artist discography | 1 album, 1 track | `01` | the album row is matched by a 4-digit year in its subtitle |
| 5 | Artist top tracks | 3 | `01` | only 5 are fetched per page |
| 6 | Saved albums / playlists / shows | 0 (6 for visuals) | Library only | 6+ makes the **See more** rows appear |
| 7 | Search hits for the pinned query | 1 track, artist, album, show, episode | `02` | a section renders only when non-empty; 5 results per section per page |
| 8 | Show episodes | 1 playable, ≥ 1 h | `02` | episodes have no Save/Seek, and duration must render `h:mm:ss` |
| 9 | Local history | 6 distinct | `01`, `04` | deduplicates by item id; `04` removes one entry per run |
| 10 | Upcoming queue | 2 | `03` | `03` swipes the row at index 1; **Clear** needs a non-empty list |
| 11 | Current item | 1 track | all | an episode has no Save, Seek or Repeat |
| 12 | Artwork | 1 per album, playlist, artist, show | every screen | demo packs bundle official covers |
| 13 | Accent colour / keypad mode | one accent per take, English, `Abc` | all | flows match English labels; T9 needs `Abc`, not `123` |

History and queue are local files (`QueueRepository`, `HistoryRepository`), not account data, so
the account only supplies library and search content. The flows are order-dependent: `01` adds
history, `02` leaves an episode current, `03` adds and removes entries, `04` removes one.

## Constraints worth remembering

- **Ordering is server-side.** Liked songs, albums, artists and shows are ordered by `added_at`
  newest first; search results use Spotify's ranking. Only the query is pinned, so "the second
  followed artist" means "followed second-most-recently".
- **Headings are conditional.** A section heading or See more row only exists when there is data
  behind it. Scroll anchors silently depend on that.
- **Duration format splits tracks from episodes.** `m:ss` under an hour, `h:mm:ss` above; `03`
  uses the track-only selector.
- **Search input is T9 multi-tap**, so the query is a digit string. Adjacent letters on the same
  key are ambiguous, which is why the query is `bowie` (`22666944433`), not `africa`.
- **Writes are real in account mode.** `02` follows and saves whatever search returns first.
- **Notices are transient** (4 s confirmation, 5 s queue Undo); flows prefer Options where they
  would race one.

## The demo build

A `demo` build type whose fixture lives in `app/src/demo/` (packs) and
`provider-spotify/.../DemoContent.kt` (interface and reflective lookup). Release, debug, profile
and dev builds are untouched: `assembleRelease` neither compiles nor ships it.

| File | Role |
| --- | --- |
| `app/build.gradle.kts` | `demo` build type, `BuildConfig.DEMO_CONTENT`/`DEMO_PACK`, debug covers in the demo source set |
| `app/proguard-rules.pro` | keeps `io.github.manuelrtsch.sidetrack.demo.**` (referenced by name) |
| `provider-spotify/.../DemoContent.kt` | interface plus the lookup that returns `null` in every normal build |
| `app/src/main/.../data/SpotifySession.kt` | serves the demo catalog and seeds history/queue when present |
| `app/src/demo/.../demo/RealPack.kt` | default pack: real songs, in-memory writer, seed |
| `app/src/demo/.../demo/FictionalPack.kt` | fully invented backup pack (`-PdemoPack=fictional`) |
| `maestro/flows/utility/record-prepare-demo.yaml` | cold launch only |

Properties:

- **Same application id and signature as release**, so installing the demo APK replaces the
  release app. Credentials survive, and the Maestro flows keep matching the production id.
- **Only the catalog is fake.** Auth and playback stay real, so a device must be signed in once;
  every item plays one stock track because the recordings are muted.
- **Default pack**: David Bowie (*Heroes*, *Low*), Nina Simone, Massive Attack, Fela Kuti and
  Buena Vista Social Club, with six bundled 512×512 covers in `app/src/demo/res/`. The pinned
  query `bowie` is satisfied in all four search sections. Real durations keep the timestamps
  right.
- **History and queue are rewritten at every process start**: nine recent tracks and a queue of
  three, which covers the "≥ 6" and "track current" requirements without a seeding flow.
- Playlists and shows are invented in both packs and use the generated `src/debug/res` covers.

## Recording with the demo build

```sh
./gradlew :app:assembleDemo
adb install -r app/build/outputs/apk/demo/app-demo.apk   # replaces the release app

# Once per device: sign in if the credentials are gone; the fixture is served on every launch.
SIDETRACK_ACCENT=Lime ./record.sh flows/promo/01-browse-play.yaml "recordings/01-browse-play.mp4"
SIDETRACK_ACCENT=Orange ./record.sh flows/promo/02-search-library-writes.yaml "recordings/02-search-library-writes.mp4"
SIDETRACK_ACCENT=Blue ./record.sh flows/promo/03-queue-transport.yaml "recordings/03-queue-transport.mp4"
SIDETRACK_ACCENT=Pink ./record.sh flows/promo/04-history-settings.yaml "recordings/04-history-settings.mp4"
```

Never set `SIDETRACK_SKIP_PREPARE=1` here: every take needs the cold launch so the previous take's
queue is discarded. Install the release APK to go back.

## Open work

- **No `flows/utility/seed-fixture.yaml`.** The account-mode fixture is manual and documented
  only. One explicit seed flow — play N distinct fixture tracks, add two to the queue, return
  Home — would make the recording chain independent of leftover state.
- **`record-prepare.yaml` pins a copyrighted track** (TOTO's *Africa*), so it cannot be reused
  with neutral content as-is.
