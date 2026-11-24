# Releasing

A release is a tag. Everything after that is automated. The one thing that must never happen
silently is shipping an APK without the native Spotify bridge.

## Why the bridge is mandatory

`assembleRelease` can legitimately build without `libsidetrack_spotify.so`: the Gradle task that
builds it is off by default because it needs a Rust toolchain and the NDK. Such a build does not
crash — Spotify falls back to the public Web API, which rate-limits the desktop client ID, so the
app looks fine and then stalls.

Three gates stand in the way:

| Gate | Where | What it proves |
| --- | --- | --- |
| `verifySpotifyNative` | Gradle, before packaging | the generated jni libs hold a real `libsidetrack_spotify.so` for every required ABI, above the size floor, exporting `NativeBridge_nativeConnect` |
| `verifyReleaseApk` | Gradle, after packaging | the packaged APK contains that `.so` for every ABI it ships |
| `scripts/verify-apk.sh` | release workflow / by hand | the artefact is signed with the Sidetrack release certificate, declares the expected package and version, and carries the bridge for exactly the advertised ABIs |

`verifySpotifyNative` and `verifyReleaseApk` hang off `assembleRelease`, so a native-less release
build fails on the machine that runs it. Debug, dev, profile and demo builds stay
native-optional.

The About screen's version row reports which engine the installed APK carries ("Native Spotify
engine" or "Spotify Web API, no native bridge"), so a tester report states it instead of requiring
a guess.

## Cutting a release

1. Merge to `main` and wait for CI. The `Native Spotify bridge` job builds the bridge and packages
   a release APK, so a broken `native/` or a packaging regression fails there.
2. Write the notes to `docs/releases/<version>.md`. This file becomes the release body verbatim;
   the workflow refuses to publish without it.
3. Tag and push:

   ```sh
   git tag -a v0.4.2 -m "Sidetrack 0.4.2"
   git push origin v0.4.2
   ```

4. The `Release` workflow checks the tag is on `main`, builds both APKs with
   `-PsidetrackNative=true`, verifies and publishes. Re-running it is safe: an existing release
   has its assets and notes replaced.

`workflow_dispatch` runs the same job for an existing tag, which is how a release is rebuilt after
a failure without force-pushing the tag.

## The app pack

SP-01 users can install Sidetrack from the
[app pack](https://manuel-rtsch.github.io/sidetrack-pack/), an F-Droid repository the
Library app adds as a source. It carries no code: it is rebuilt from these releases on every
run, and its signing key is separate from the APK key (it signs the index, not the app). The
pack's own README is the runbook.

The release workflow's last step asks the pack to rebuild via `repository_dispatch`. That step is
in the workflow rather than a release-triggered workflow because an event produced with
`GITHUB_TOKEN` does not start another workflow — the trigger would never fire. If
`APP_PACK_DISPATCH_TOKEN` is unset the step warns and exits, and the pack's weekly cron picks the
release up instead. A release is never failed by the pack.

Since 0.4.4 a release carries **one** APK, `Sidetrack-<version>-arm64-v8a.apk`, plus
`SHA256SUMS.txt`. `scripts/release.sh` packages `-PsidetrackAbis=arm64-v8a`, which is every SP-01's
ABI, halves the download, and matches what the app pack can index — an F-Droid index holds one file
per `versionCode`, so a second asset was one the pack could never serve. The universal build is no
longer published; an emulator or an x86_64 phone gets one from
`./gradlew :app:assembleRelease -PsidetrackNative=true` without `-PsidetrackAbis`.

## Repository secrets

| Secret | Contents |
| --- | --- |
| `SIDETRACK_KEYSTORE_BASE64` | the release `.jks`, `base64 -w0` |
| `SIDETRACK_STORE_PASSWORD` | `storePassword` |
| `SIDETRACK_KEY_ALIAS` | `keyAlias` |
| `SIDETRACK_KEY_PASSWORD` | `keyPassword` |
| `APP_PACK_DISPATCH_TOKEN` | fine-grained PAT, `contents: write` on `manuel-rtsch/sidetrack-pack` only |

The workflow writes `keystore.properties` (gitignored) from them, the same file
`app/build.gradle.kts` reads locally. The certificate is pinned in `scripts/verify-apk.sh`, so an
APK signed with a different key fails the release rather than shipping — Sidekit trusts the app as
a host only by that certificate.

## Building by hand

The workflow has no exclusive logic: it calls `scripts/release.sh`, and so can you.

```sh
scripts/release.sh 0.4.2
```

It requires `cargo`, `cargo-ndk`, the NDK and `ANDROID_HOME`, a clean tree, and `HEAD` to describe
exactly as `v<version>` (`versionName` comes from `git describe`). The output lands in
`build/release/` with `SHA256SUMS.txt`, and the APK has already been through
`scripts/verify-apk.sh`. Publishing by hand is then:

```sh
gh release create v0.4.2 \
  build/release/Sidetrack-0.4.2-arm64-v8a.apk \
  build/release/SHA256SUMS.txt \
  --notes-file docs/releases/0.4.2.md
```

## Local iteration

`./gradlew :app:assembleDebug` (or `installDev`) builds without Rust or the NDK, which keeps the
loop fast. Add `-PsidetrackNative=true` when the change touches `native/` or the Spotify bridge.
