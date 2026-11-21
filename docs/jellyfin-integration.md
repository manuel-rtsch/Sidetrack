# Jellyfin integration

Jellyfin is a provider in `:provider-jellyfin`, built on `org.jellyfin.sdk:jellyfin-core` 1.9.4
(LGPL-3.0). The SDK is a generated API client only: URL builders, models, WebSockets, discovery.
There is no player, queue or cache, so playback stays on the shared Media3 engine.

## Server floor: Jellyfin 12.0+

SDK 1.9.x is generated against API 12.4 and declares 12.0.0 as its minimum, but it does not refuse
an older server by itself: on 10.9.11 the password sign-in succeeds and the first `GET /Items`
fails in deserialization (`UserItemDataDto` has no `ItemId`, which the SDK requires). Every screen
then fails on a session that looked fine.

`SdkJellyfinAuthenticator` therefore asks the anonymous `/System/Info/Public` **before** sending
the password and rejects an older server by name ("That server runs Jellyfin 10.9.11. Sidetrack
needs Jellyfin 12.0 or newer."). An unreadable version string (fork, proxy) is let through and its
reads fail honestly. This is a floor, not a preference: revisit only by adding a second client.

## Pieces

Paths are relative to `provider-jellyfin/src/main/java/io/github/manuelrtsch/sidetrack/`.

| Piece | File | Responsibility |
| --- | --- | --- |
| API seam | `data/JellyfinApi.kt` | reduces SDK DTOs to provider records; `SdkJellyfinApi` is the only class touching the SDK |
| Catalog and writer | `data/JellyfinCatalog.kt` | `MediaCatalog` + `MediaLibraryWriter` over the seam |
| Auth | `auth/JellyfinAuth.kt` | `ServiceAuth` over `authenticationApi`, token-only store |
| Store | `auth/JellyfinCredentialStore.kt` | server, token, user id and name in `GuardedEncryptedStore` |
| Runtime | `data/JellyfinRuntime.kt` | `ServiceRuntime` + quality/cache mix-ins, one `createApi` per account |

Registration stays in `:app` (`KnownServices`, `AppSession.build`); `ServiceId` and
`ServiceCapabilities` live in `:core`.

## Playback path

1. `mediaInfoApi.getPostedPlaybackInfo(itemId, PlaybackInfoDto(userId, maxStreamingBitrate,
   deviceProfile = musicProfile))`. Take `playSessionId` (needed for progress) and the first
   media source. Play `transcodingUrl` (relative to `api.baseUrl`) with `requiredHttpHeaders`
   plus the `Authorization` header.
2. No `transcodingUrl`: direct play is `…/Audio/{id}/stream?static=true`, which serves the
   original file with byte ranges, so FLAC stays FLAC and seeking is a `Range` request.
3. **Never** use `getUniversalAudioStreamUrl` as a fallback: without a container it answers 500
   with an HTML body, and Media3 reports "none of the available extractors could read the stream".
   That was the Jellyfin playback bug the posted-info path exists to prevent.
4. `AudioQuality` maps to `maxStreamingBitrate`: `Normal` 96 kbit/s, `Max` 320 kbit/s, `High`
   uncapped. The music `DeviceProfile` must state the ceiling explicitly, including for `High`, or
   the server may transcode FLAC to MP3 silently. Every cap is a transcode.

## Artwork headers

The SDK's URL builders do not append a token, and Sidetrack does not put `ApiKey` in image URLs.
`RemoteArtwork`/`RemoteArtworkImage` take a `headers` map (Sidekit 1.9.0), the map is part of the
memory and disk cache key, and its values are hashed — never written to a file name or a log line.
The signed-in service's headers sit in `ArtworkAccess`, installed at sign-in and cleared at
sign-out, so rows, the now-playing backdrop and the media notification send the same
`Authorization` as the audio stream. Spotify and the Subsonic family pass an empty map.

## Progress and "played"

- `postFullCapabilities` once after sign-in, so the server knows the client and its profile.
- `reportPlaybackStart` on start, `reportPlaybackProgress` every ~10 seconds and on pause/seek,
  `reportPlaybackStopped` on stop.
- `markPlayedItem` only when the engine emits its end-of-track signal: a skip stops but never
  counts as a play.
- `getResumeItems` seeds the local `ResumePoint` at sign-in; local state is authoritative after
  that.

## Deliberately absent

- **Podcasts/shows** — a music library has no podcast type.
- **Loudness normalization** — no ReplayGain or loudness parameter on any endpoint.
- **Gapless** — engine-level, like every non-Spotify service.
- **Quick Connect** — password sign-in only; the password field is optional, so accounts without
  one can still sign in.
- **WebSockets and SDK discovery** — a like made in another client is not reflected until the next
  refetch; the plain server-URL form is enough for v1.

## Tests

`provider-jellyfin` runs offline unit tests by default against `FakeJellyfinApi`, plus direct
DTO-mapper and playback-URL tests. The integration suite is opt-in:

```sh
JELLYFIN_TEST_URL=http://127.0.0.1:8096 \
JELLYFIN_TEST_USER=demo JELLYFIN_TEST_PASSWORD=demo \
./gradlew :provider-jellyfin:testDebugUnitTest -PsidetrackIntegration --tests '*JellyfinIntegrationTest'
```

It skips when the variables are absent and is read-only. A throwaway `jellyfin/jellyfin:12.x`
container is the reference; never point it at a real instance.
