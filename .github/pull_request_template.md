<!--
A PR here is one behavior, explained. The body is the review: say what was wrong, what it is now
and what proves it. Conventional Commit style in the title (`fix(queue): …`, `feat(search): …`).
Delete a section only when the PR genuinely has nothing for it — say so rather than leaving it blank.
-->

## What was wrong

<!-- The behavior before, on the phone. If it was a bug, how did it show? -->

## What it is now

<!-- The behavior after. Name the screen, the row, the key. -->

## Keypad and touch

<!-- Every action has to work from both. Which key or hold reaches the new path, and where is the
touch equivalent? If the change is keypad-only, say why touch has no counterpart. -->

## Testing

Commands actually run, not the ones that might pass. Note anything left to a device.

- [ ] `./gradlew testDebugUnitTest`
- [ ] `./gradlew coverage` — floors raised where coverage grew, never lowered to absorb a regression
- [ ] Screenshot baselines re-recorded with `-PsidetrackScreenshotsRecord`, and only the screens this PR changes are committed
- [ ] `./gradlew spotlessApply`
- [ ] `./gradlew lintDebug lintDemo` when the change touches app code or `src/demo`
- [ ] `./gradlew cpdCheck` when the change adds or moves blocks of code
- [ ] On a device: the keypad path by hand and/or `./gradlew :app:connectedDebugAndroidTest`
- [ ] `./gradlew :app:assembleProfile -PsidetrackNative=true` when `native/` or packaging changes
- [ ] `-PsidetrackIntegration` against a reachable server when a provider API mapping changes

## Screenshots

| Before | After |
| --- | --- |
|  |  |

<!-- Drop the baseline PNGs (or device captures) in the table. Visual PRs are reviewed from these
first and the diff second. -->

## Docs

<!-- Which of docs/architecture.md, docs/menus.md, docs/provider-capabilities.md, docs/testing.md,
docs/releasing.md changed, and why. A release note lands in docs/releases/<version>.md on the tag,
not here. -->

## Boundaries

- [ ] Providers depend on `:core` only, and `:core` names a service only through `ServiceId` (`docs/architecture.md`)
- [ ] A new destination or row action follows the Options bar rules in `docs/menus.md`
- [ ] No local-only files are included: `AGENTS.md`, `docs/.adb-without-cable.md`, `keystore.properties`, `local.properties`, `crash-logs/`, `/tools/pmd/`
- [ ] New third-party code is licensed compatibly and pinned by version, action or SHA
