# libsidetrack_spotify

The Sidetrack native bridge. It links librespot 0.8.0 and exposes the JNI surface declared in
`bridge/NativeBridge.kt`.

## Toolchain

- Rust stable.
- Android targets: `aarch64-linux-android`, `x86_64-linux-android`.
- `cargo-ndk`.
- Android NDK 27.1.12297006 (the version pinned by the app module).

## Build

Gradle builds it when native playback is enabled with `-PsidetrackNative=true`:

```sh
./gradlew :app:buildSpotifyNative -PsidetrackNative=true
```

To run Cargo directly:

```sh
export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/27.1.12297006"
cargo ndk -t arm64-v8a -t x86_64 \
  -o ../../app/build/generated/spotifyJniLibs build --release
```

The output directory is a `jniLibs` source set. Without `-PsidetrackNative=true`, Cargo, or the NDK, Gradle skips
the task and the APK has no library; `NativeLibraries.load("sidetrack_spotify")` then returns false.

## Dependency pins

`Cargo.lock` pins librespot and the full graph. `vergen` is held at 9.0.6 because
`vergen-gitcl` 1.0.8 depends on `vergen-lib` 0.1.6 while `vergen` 9.1.0 pulled a second
incompatible `vergen-lib`. Do not update that crate without rebuilding Android targets.

One minimal librespot-core patch is applied through `native/vendor/librespot-core` (platform
constant set to `"linux"` so Login5/client-token requests match the desktop client id).
Upstream MIT licence: `LICENSE-librespot`.

`native/vendor/hyper-proxy2` carries the other patch, and it exists to keep one TLS stack in the
APK. librespot-core routes every request through `hyper-proxy2` for proxy support, and the only
published release (0.1.0, Jan 2024) pins `hyper-rustls` 0.26, which resolves to `rustls` 0.22 and
`rustls-webpki` 0.102. Those two carried seven Dependabot advisories while librespot's own
`hyper-rustls` 0.27 already shipped the fixed `rustls` 0.23. Upstream moved to 0.27 in commit
`ey/bump-tokio-rustls` but never cut a release, so the bump is vendored here:

- `hyper-rustls` 0.27, `tokio-rustls` 0.26, `rustls-native-certs` 0.8, `webpki-roots` 1.0.
- `hyper-rustls` and `tokio-rustls` are taken with `default-features = false` and `ring` enabled.
  Both switched their default crypto provider to `aws-lc-rs` in these majors, which would drag
  `aws-lc-sys` and `cmake` into the NDK build; librespot already selects `ring`.
- The `webpki` 0.22 dependency is dropped. Nothing in the crate references it — it was only ever
  listed in the `rustls-base` feature — and it is the crate the advisories were about.

The `Cargo.lock` now resolves a single `rustls` 0.23.46 and `rustls-webpki` 0.103.16. When
librespot is upgraded, drop this patch if the release depends on a `hyper-proxy2` that already
uses `hyper-rustls` 0.27, or re-apply the same bump to the new source.
Upstream MIT licence: `LICENSE-hyper-proxy2`.
