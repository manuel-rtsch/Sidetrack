#!/usr/bin/env bash
#
# Builds the published APK for a release, verifies it, and writes SHA256SUMS.txt.
#
# It packages arm64-v8a only. That is the ABI of every SP-01, the app pack's index can hold one
# file per versionCode anyway, and dropping x86_64 halves the download. An emulator build is a
# local `./gradlew :app:assembleRelease -PsidetrackNative=true` without -PsidetrackAbis; it is not
# a release artefact.
#
# This is the single path a release takes: the release workflow calls it, and running it by hand
# produces byte-identical inputs for the checks the workflow would run. Native playback is
# mandatory here — a release without librespot silently falls back to the rate-limited Web API,
# which is exactly the regression the gate in app/build.gradle.kts exists to prevent.
#
# Requires: cargo + cargo-ndk, the Android NDK, and the Android SDK build-tools on ANDROID_HOME.
# Signing comes from keystore.properties in the repository root (see app/build.gradle.kts); in CI
# the workflow writes that file from secrets.
#
# Usage: scripts/release.sh <version>      e.g. scripts/release.sh 0.4.1

set -euo pipefail

version=${1:?usage: release.sh <version, e.g. 0.4.1>}
version=${version#v}
# Spelled as an `if`, not `[[ ... ]] || printf ... && exit 1`: `||` and `&&` share precedence and
# bind left to right, so the one-line form exits 1 when the version is *valid*. A release run then
# dies silently before it prints anything.
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
  printf 'release: %s is not a version like 0.4.1\n' "$version" >&2
  exit 1
fi

cd "$(dirname "$0")/.."
readonly repo_root=$PWD
readonly out="$repo_root/build/release"
readonly apk_dir="$repo_root/app/build/outputs/apk/release"

command -v cargo >/dev/null || { printf 'release: cargo is not on PATH\n' >&2; exit 1; }
cargo ndk --version >/dev/null 2>&1 || { printf 'release: cargo-ndk is not installed\n' >&2; exit 1; }
[ -f "$repo_root/keystore.properties" ] ||
  { printf 'release: keystore.properties is missing; the APK would be unsigned\n' >&2; exit 1; }

# `git describe` feeds versionName, so the tag has to exist before the build, not after.
described=$(git describe --tags --match 'v[0-9]*' --dirty)
[ "$described" = "v$version" ] ||
  { printf 'release: HEAD describes as %s, expected v%s (tag it, or check out the tag)\n' \
      "$described" "$version" >&2; exit 1; }

if ! git diff --quiet || ! git diff --cached --quiet; then
  printf 'release: the working tree is dirty; versionName would carry -dirty\n' >&2
  exit 1
fi

rm -rf "$out"
mkdir -p "$out"

run_gradle() {
  ./gradlew :app:assembleRelease -PsidetrackNative=true "$@" --stacktrace
}

# --- the one published asset: arm64-v8a only --------------------------------------------------
run_gradle -PsidetrackAbis=arm64-v8a
phone="$out/Sidetrack-$version-arm64-v8a.apk"
cp "$apk_dir/app-release.apk" "$phone"
scripts/verify-apk.sh "$phone" "$version" arm64-v8a

(cd "$out" && sha256sum Sidetrack-"$version"-arm64-v8a.apk > SHA256SUMS.txt)

printf '\nrelease: artefacts in %s\n' "$out"
ls -l "$out"
cat "$out/SHA256SUMS.txt"
printf '\nNext, if publishing by hand: gh release create v%s %s %s --notes-file docs/releases/%s.md\n' \
  "$version" "$phone" "$out/SHA256SUMS.txt" "$version"
