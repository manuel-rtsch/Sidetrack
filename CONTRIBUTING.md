# Contributing to Sidetrack

Sidetrack is a music player for the [Sidephone SP-01](https://www.sidephone.com/) built around its
keypad. It is a small project with one maintainer, so the fastest contribution is one that already
answers the questions review would ask: which build, which service, which keypad.

Bug reports and feature requests have a form each — pick one instead of opening a blank issue. This
file covers the code side.

## Before you start

- **A bug** goes in the bug form. It asks for the version, the service, the keypad module and the
  About screen's Spotify-engine row, because a report missing any of those usually cannot be
  reproduced.
- **A feature** starts in the feature form, and in [TODO.md](TODO.md). TODO records what is
  deliberately deferred; a PR that flips a capability flag before the engine work behind it exists
  will be asked to turn it back off.
- **Talk first** for a new provider, a new dependency, a change across a module boundary, or a
  redesign of the Options bar or the keypad chrome. Those are the changes where a design discussion
  is cheaper than a rewritten PR.
- **Just open the PR** for a crash, a layout bug, a typo or a one-screen fix.

## Setting up

You need **JDK 21** and the **Android SDK** (platform 37, build-tools 37.0.0). Android Studio works;
the command line is enough.

```sh
git clone git@github.com:manuel-rtsch/Sidetrack.git
cd Sidetrack
./gradlew :app:assembleDebug
```

`keystore.properties` is not in the repository; without it a `release` build stays **unsigned**, so CI
can sign. Debug and profile are signed with the debug key, which is all a local build needs — the
debug app carries a `.dev` identity, so it installs next to a release install rather than replacing it.

**Sidekit** comes from its published GitHub Pages Maven, which is the source of truth. Only when
you are changing Sidekit itself, point the build at a sibling checkout:

```sh
./gradlew :app:assembleDebug -PsidetrackLocalSidekit=true
```

**The native Spotify bridge** needs Rust (stable) and the Android NDK. It is optional for debug,
dev, profile and demo builds, and mandatory for a release:

```sh
./gradlew :app:assembleProfile -PsidetrackNative=true
```

## Rules the code is held to

These are not style preferences; CI and review enforce them.

1. **Every action works from the keypad and equally from touch.** A new path needs a key, a hold or
   an Options entry, and a touch equivalent. If it cannot have one, the PR says why.
2. **Providers depend on `:core`, never the reverse.** `:core` names a service only through
   `ServiceId` and the capability sets; only `:app` knows every provider. Read
   [docs/architecture.md](docs/architecture.md) before moving anything across that line.
3. **One menu primitive.** Screens publish `SideAction`s; there are no Material menus, dropdowns or
   sheets. [docs/menus.md](docs/menus.md) has the shape and tone rules.
4. **Pure logic belongs in `:core`.** A test that needs a `Context` or Compose belongs in the module
   that owns the code. Provider behavior is tested in the provider module, not in `:app`.
5. **Coverage floors are a ratchet.** Raise a floor when a change earns coverage; never lower one to
   absorb a regression. The floors and what runs them are in [docs/testing.md](docs/testing.md).
6. **Formatting is automated.** `./gradlew spotlessApply` before you push; ktlint is the arbiter.
7. **Documentation is part of the change.** When behavior moves, the matching file moves with it:
   `docs/architecture.md`, `docs/menus.md`, `docs/provider-capabilities.md`, `docs/testing.md`.
8. **Local-only files stay out of git.** `AGENTS.md`, `docs/.adb-without-cable.md`,
   `keystore.properties`, `local.properties`, `crash-logs/` and `tools/pmd/` are ignored, and a PR
   that carries one is a `.gitignore` bug to fix rather than a file to review.

## Commands

| Command | What it does |
|---|---|
| `./gradlew testDebugUnitTest` | the default gate: Robolectric unit tests |
| `./gradlew coverage` | the default gate plus every coverage floor |
| `./gradlew testDebugUnitTest -PsidetrackScreenshots` | adds the Roborazzi baseline verifications |
| `./gradlew testDebugUnitTest -PsidetrackScreenshotsRecord` | rewrites baseline PNGs instead of verifying them |
| `./gradlew spotlessApply` | formats Kotlin (and `spotlessCheck` in CI fails without it) |
| `./gradlew lintDebug lintDemo` | Android Lint, including the demo source set |
| `./gradlew cpdCheck` | copy/paste budget against `sidetrack.cpd.maxDuplicateLines` |
| `./gradlew :app:connectedDebugAndroidTest` | instrumented tests on a device or emulator |
| `./gradlew testDebugUnitTest -PsidetrackIntegration` | live-server tests; each skips itself without its env vars |

Screenshot tests verify by default and record only when asked. If you re-record, commit **only** the
screens your change moved — a PR that rewrites every baseline hides the change it is meant to show.

## Commits and pull requests

Branch from `main`. `release/*` is the maintainer's, cut from a tagged release.

Titles follow Conventional Commits with a scope, matching the existing history:

```
fix(queue): own the touch lift on the list and hold the viewport still
feat(search): keep the corner holds working on the category row
docs(github): ask a report for the service, the keypad and the log
```

The body explains **why** the change exists and what was wrong before it; the diff explains what
changed. One behavior per PR — a refactor and a fix in one branch is two reviews fighting.

Fill in the pull-request template. The two sections reviewers read first are **Keypad and touch**
and **Testing**, and the checklist wants the commands you actually ran, not the ones that would
have passed. Screenshots go in the before/after table for anything visual.

Wait for CI. Every job is a gate, including the native-bridge build, the instrumented tests on both
API levels and the secret scan. A red job is not something to merge past because the change "is
only docs".

## Reporting a bug from the phone

Playback bugs need the app's rolling file log; `docs/testing.md` explains how to enable it on a
release build, where it lives, and why it must be pulled before the next repro. Logcat alone keeps
too little of this app's output once the Spotify bridge starts writing PCM counters.

Device access is normally `adb` over a cable. Without one, there are notes on the Bluetooth-PAN
route, but they are local-only and deliberately not published: ask the maintainer rather than looking
for them in the tree.

## Releases

Releases are tags, and the maintainer cuts them: [docs/releasing.md](docs/releasing.md). Do not bump
version numbers or add `docs/releases/<version>.md` in a normal PR; release notes are written for the
tag, and the workflow refuses to publish without them.

## Licence

Sidetrack is MIT-licensed, and contributions are accepted under the same terms; there is no CLA.
The Spotify native bridge links librespot and ships its licence and notice with the build. Note the
one exception recorded in the [README](README.md#licence): the Spotify auth package is currently a
derivative of the GPLv3 Sidespot auth path and is pending a clean-room rewrite or a GPLv3 notice.

## Code of Conduct

Participation is covered by the [Code of Conduct](CODE_OF_CONDUCT.md). Reports go privately to the
maintainer.
