# Provider capabilities

Sidetrack speaks to one service at a time through a provider-neutral domain model. The UI never
names a provider; it asks a service's `ServiceCapabilities` which features to show.

Sources of truth:

- `core/.../MediaModels.kt` — `ServiceCapabilities`, `ServiceId`, `PlaylistActions`.
- `MediaModels.kt` also defines which categories a service offers (`availableLibraryFilters`,
  `availableSearchScopes`), fed to the Library row and the search screens.
- `ServiceCapabilities.playlistActions(...)` combines the service-level flag with a playlist
  row's own answer; the result travels on `Playlist.actions` and `Collection.playlistActions`.
- Per-provider sets and overrides live in the provider modules.

Legend: ✅ supported & surfaced · ⚠️ implemented but not surfaced · ❌ not supported.

A capability that is off keeps its button in place but mutes it: no click, skipped under the
keypad, announced as "not available". The row never reflows between services, and no button
leads to an empty page.

## Overview

| Capability | Spotify | Subsonic family | Jellyfin | Plex | Local library |
| --- | :---: | :---: | :---: | :---: | :---: |
| Library sections | Liked, Playlists, Albums, Artists, Shows | Liked, Playlists, Albums, Artists | Liked, Playlists, Albums, Artists | Liked, Playlists, Albums, Artists | Liked, Playlists, Albums, Artists |
| Search scopes | Tracks, Artists, Albums, Playlists, Shows | Tracks, Artists, Albums, Playlists | Tracks, Artists, Albums, Playlists | Tracks, Artists, Albums, Playlists | Tracks, Artists, Albums, Playlists |
| Podcasts / shows | ✅ | ❌ | ❌ | ❌ | ❌ |
| Song radio | ✅ | ✅ | ✅ | ❌ | ❌ |
| Follow artist | ✅ | ❌ | ✅ | ❌ | ❌ |
| Playlist item edits | ✅ per playlist | ✅ owned playlists | ✅ offered unconfirmed | ✅ writable playlists | ✅ Sidetrack-owned files |
| Playlist creation | ❌ | ✅ | ✅ | ❌ | ✅ as `.m3u8` |
| Playlist rename / delete | ✅ owner only | ✅ owner only | offered unconfirmed | ✅ non-smart | ✅ Sidetrack-owned files |
| Loudness normalization | ✅ | ✅ | ❌ | ❌ | ❌ |
| Gapless playback | ✅ | ❌ | ❌ | ❌ | ❌ |
| Quality selection | ✅ | ✅ | ✅ | ❌ | ❌ |
| Progress reporting (resume) | ✅ | ✅ | ✅ | ✅ | ❌ (resume is local) |
| Scrobbling | ✅ | ✅ | ✅ | ✅ | ❌ |
| Sign-in | OAuth (PKCE) in browser | Username/password against a `ping` | Username/password against the SDK | PIN via plex.tv in browser | Media-storage permission |

## Playlist actions are per playlist, not just per service

`ServiceCapabilities.supportsPlaylistEdit` says a service has an item-edit transport. It does not
say whether the signed-in account may change a particular playlist. That answer is
`PlaylistActions`:

| Field | Meaning |
| --- | --- |
| `canAddItems` | Appending to this playlist is offered. |
| `canRemoveItems` | Removing an entry from this playlist is offered. |
| `canRename` | Renaming the playlist is offered (owner only on Spotify/Subsonic). |
| `canDelete` | Deleting the playlist is offered (owner only on Spotify/Subsonic). |
| `unconfirmed` | The service did not state permissions; the action is offered and a server refusal is reported. |

Add and remove stay separate because a service can grant one without the other. `Playlist.editable`
remains as a compatibility view (`canAddItems || canRemoveItems`); new UI reads the pair.
`Collection.playlistActions` is the same answer once a playlist page is open, so the row list and
the page cannot disagree.

Rename and delete are item permissions too. Spotify reports both only for playlists the account
owns; Subsonic derives them from the same owner rule as add/remove; Jellyfin offers them
unconfirmed; Plex offers them for every non-smart playlist. Spotify's delete is its library write
(remove from the account's library), since that is the only delete Spotify exposes.

| | Spotify | Subsonic family | Jellyfin | Plex |
| --- | --- | --- | --- | --- |
| Source of truth | playlist capabilities + owner | playlist `owner` vs signed-in username | no reliable server answer | `smart` flag |
| Owned playlist | add + remove + rename + delete | add + remove + rename + delete | offered unconfirmed | add + remove + rename + delete |
| Collaborative / foreign | `can_edit_items` grants add + remove; rename/delete stay owner-only | read-only | offered unconfirmed | add + remove + rename + delete |
| Followed, non-collaborative | read-only | read-only | offered unconfirmed | — |
| Smart / generated | — | — | — | read-only |
| Cannot report | read-only (`editable=false`) | offered unconfirmed | offered unconfirmed | — |
| Read-only fallback | Web API transport → all playlist capabilities off | — | — | `PlaylistActions.None` |

### Playlist item edits

`PlaylistActions` is computed for every playlist row and page. The row bar stays inside the
shared option budget: two labelled actions, never a third square.

- **Create** — *New playlist* in Library → Playlists opens a form with a name and up to ten
  recently played tracks as an optional seed. Nothing is selected until tapped. On success the
  new playlist opens, empty or seeded in the order shown. A refused or failed create stays on the
  form and reports the service message; it never fails silently. Gated by
  `supportsPlaylistCreate`.
- **Add** — a track row outside a playlist offers `Play | Add to queue | Add to playlist`. The picker
  lists only playlists whose `canAddItems` is true. Adding appends and the writer reports the index
  the service placed the row at; the bar says "Added track to X" and offers no Undo, because the
  add is a creation and Undo is reserved for destructive edits. Episodes are excluded because
  playlists are track-only.
- **Remove** — a track row inside a playlist offers `Play | Add to queue | Remove from playlist` when
  `canRemoveItems` is true. Removal cannot restore the original slot, so it uses a two-step
  `Cancel | Remove` confirmation rather than Undo. The row page drops the confirmed occurrence
  and keeps the paging offset aligned.
- **Rename** — a playlist page's Options menu offers *Rename* when `canRename` is true. The page
  title becomes a field; Enter / the keypad's Done key commits.
- **Delete** — the same menu offers *Delete* in the destructive red when `canDelete` is true.
  The write is deferred until the Undo window ends, so Undo cancels it. Leaving the page commits
  the deletion through the view model.
- **Reordering** is not offered: Now Playing already sits at its three-option cap, and no
  provider has a reorder transport.

`MediaLibraryWriter.addToPlaylist` has no optional index, so a remove cannot be undone back into
its original position. That is why remove confirms instead of offering Undo.

## Spotify

`ServiceCapabilities.All`: every capability this build knows.

- Library: liked/saved tracks, playlists, albums, artists, shows.
- Search across all five scopes.
- Podcasts: show notes, episode numbers and dates, per-episode resume, ±30-second controls, no
  song-style shuffle or repeat.
- Playback: play/pause/skip/seek/shuffle/repeat/volume, queue with reorder and history,
  normalization, gapless, quality selection.
- Radio: song radio from any seed.
- Saving: like tracks, save albums and shows, follow artists, and add or remove playlist items on
  a playlist the account owns or collaborates on. A refused write is `ServiceFailure.NotAllowed`,
  not a network error.
- Progress and scrobbling.
- Playlist creation is off: the native bridge has no create endpoint.

Spotify library and search need the native bridge; without it the Web API transport is read-only
and rate-limited. Only a transport that cannot reach the access point moves reads there: an answer
the service itself refuses (a 5xx gateway error, say) is reported as that request's failure instead,
and a degraded transport is probed again once a minute so a hiccup at launch does not cost the
process.

## Subsonic family (Navidrome, Nextcloud Music, Subsonic)

One adapter over the Subsonic/OpenSubsonic API. `SubsonicFamily` is the only thing that varies
between picker entries: `ServiceId`, display name, optional public demo, and the derived
credential/settings/queue/history store names. `AppSession.build` routes every unknown service id
through `SubsonicFamily.of(id)`, so another compatible server is one enum entry. Any entry
connects to any Subsonic server; the id keeps two servers on one device apart in `MediaKey`, the
credential store and the local files. Only Navidrome offers the public demo.

Implemented:

- **Auth**: username/password against a `ping`, using the Subsonic token (MD5 salt + password);
  credentials in `SubsonicCredentialStore`.
- **Library**: starred tracks, playlists, albums, artists; album and playlist collections with
  pagination; artist detail, albums and top tracks.
- **Search**: `search3` mapped per scope.
- **Playback**: authenticated `stream` URL through the shared `PlaybackController` over Media3, so
  the media session, notification and audio focus behave as for every other service.
- **Writes**: star/unstar, create playlist, add to / remove from playlists. Removal takes the row
  the caller acted on (`index: Int? = null`): Subsonic removes by position, so with a duplicate
  track the named row decides; without one the last match goes. A row that no longer holds the
  item is ignored.
- **Playlist permissions**: no per-playlist flag, so the adapter compares `owner` with the
  signed-in username. A foreign playlist is `PlaylistActions.None` and the writer refuses before
  sending `updatePlaylist`. An ownerless playlist is offered unconfirmed; a playlist the account
  just created is always editable.
- **Radio**: `similarSongs`, with `randomSongs` as fallback for servers without Last.fm.
- **Quality**: `AudioQuality` maps to `maxBitRate` on the stream URL. `High` leaves the server
  free to pick (so FLAC is not capped); `Normal` → 96 kbit/s, `Max` → 320 kbit/s. The ceiling
  applies to the next track.
- **Normalization**: `replayGain=true` when the toggle is on.
- **Progress and scrobbling**.
- **Isolation**: separate stores per entry.

Not supported:

- **Podcasts** — Navidrome registers the Subsonic podcast endpoints as HTTP 501 stubs; upstream
  support is unmerged, so there is nothing to read.
- **Follow artist** — `isFollowed` returns `null`, `setFollowed` is a no-op.
- **Gapless** — waits for Media3 to hand tracks over seamlessly. The preference is already
  persisted, ready to flip on.

## Jellyfin

Implemented through `org.jellyfin.sdk:jellyfin-core` 1.9.4 (LGPL-3.0), which speaks Jellyfin
12.x; music libraries only. Servers older than 12.0 are refused at sign-in by version, because
10.x accepts the sign-in and then fails every read inside the SDK. See
[jellyfin-integration.md](jellyfin-integration.md).

- **Auth**: `/Users/AuthenticateByName`, session token in `JellyfinCredentialStore`, SDK
  `AuthorizationHeaderBuilder` for header consistency. `restore` reads the token without a round
  trip; a revoked token becomes `AuthenticationExpired` on the first request.
- **API seam**: `JellyfinApi` reduces SDK DTOs to provider records; `SdkJellyfinApi` is the only
  class that touches the SDK.
- **Library**: favourite tracks, playlists, albums, favourite artists; album and playlist
  collections with pagination; artist detail, albums, top tracks; artwork URLs minted per call.
- **Search**: `getSearchHints` split by scope; a hint's artwork uses the image item id it points
  at.
- **Playback**: `getPostedPlaybackInfo` with a music `DeviceProfile` decides direct play vs
  transcode; the resolved URL and headers go through the shared Media3 path. Quality maps to
  `maxStreamingBitrate`; `High` leaves the ceiling to the server. Artwork and audio carry
  `Authorization` via `ArtworkAccess` / `PlaybackSource`.
- **Writes**: favourite/unfavourite tracks, albums and artists (Jellyfin has no separate "saved"
  flag), create a playlist, add and remove items.
- **Playlist permissions**: unreliable on the server, so every playlist is
  `PlaylistActions.ServerDecides` (`unconfirmed`): the action is offered, the server can still
  refuse.
- **Radio**: `getInstantMixFromItem` from any seed.
- **Progress**: `reportPlaybackStart` / `Progress` / `Stopped`, throttled to 10 seconds, with
  `markPlayedItem` only on the engine's end-of-track signal. A skip reports a stop but never a
  play. Resume is seeded from `getResumeItems` at sign-in.
- **Isolation**: separate stores per service.

Not supported: podcasts, loudness normalization (no ReplayGain parameter), gapless, Quick
Connect, and any server older than 12.0.

## Plex

Implemented against the Plex Media Server HTTP API; sign-in is a PIN on plex.tv, so Sidetrack
never sees the Plex password. Only `artist` sections are music, and the adapter reads the first
one. See [plex-integration.md](plex-integration.md) for the wire decisions.

- **Auth**: PIN flow on plex.tv, then the first reachable claimed server from
  `/api/v2/resources`. Session data lives in `PlexCredentialStore`; a revoked token becomes
  `AuthenticationExpired` on the first request.
- **API seam**: `PlexApi` reduces JSON `MediaContainer`s to provider records; `HttpPlexApi` is
  the only class that speaks the wire format.
- **Library**: liked tracks (`userRating>=8`), playlists, albums, artists; album and playlist
  collections with pagination; artist detail, albums and all tracks.
- **Search**: `/hubs/search` hubs split by scope in one request.
- **Playback**: the direct part URL with the token header, through the shared Media3 path.
  Artwork uses the owning item's thumb and the same token.
- **Writes**: favourite/unfavourite tracks, albums and artists by setting the five-star rating;
  add to and remove from writable playlists. Removal resolves the `playlistItemID` for the named
  row, so a duplicate removes the right copy.
- **Playlist permissions**: a smart playlist is `PlaylistActions.None`; every other playlist
  accepts item edits. Creation needs a non-empty `uri` seed, so the entry point stays off.
- **Progress**: `/:/timeline` on start, pause, resume and every 10 seconds, `/:/scrobble` only on
  the engine's end-of-track signal.
- **Isolation**: separate stores per service.

Not supported: podcasts, song radio (the server generation returns no mix), follow artist,
playlist creation, loudness normalization, gapless, quality selection (v1 plays the original
part).

## Local library

The music already on the phone, read through `MediaStore` rather than a directory walk: the media
database is the index over the music directory, it is what scoped storage lets an app read, and its
ids are the identity a queue and a resume point can persist across a rescan. Playback resolves to
`content://media/external/audio/media/<id>`, which Media3 plays like any other source, so the media
session, notification, audio focus, queue, history and resume point are the shared runtime's.

Implemented:

- **Sign-in**: `SignInKind.Device` — no account, no server, no stored secret. The screen asks for
  `READ_MEDIA_AUDIO` (Android 13+) or `READ_EXTERNAL_STORAGE` (12), and `LocalAuth` re-checks the
  grant, so a refused dialog reports the same way a refused password does. Signed out for the
  process only; the platform keeps the grant.
- **Library**: albums and artists are groupings of the same track rows (an album is its
  `ALBUM_ID`, an artist its name), so the sections cannot disagree while a rescan runs. Liked is a
  local id list — the device has no favourite flag — and liking an album likes its tracks.
- **Search**: one pass over the snapshot, filtered per requested scope. Playlist scope matches the
  playlist's name.
- **Playback**: the MediaStore row, through the shared Media3 path. Duration, title, artist, album
  and album art (`content://media/external/audio/albumart/<albumId>`) come from the database.
- **Playlists, read**: system playlists (`MediaStore.Audio.Playlists`) are listed as
  `store:<id>`, with `PlaylistActions.None`. Since Android 11 only the app that created a playlist
  row may change it, and membership writes vary by OEM, so this adapter does not pretend otherwise.
- **Playlists, written**: Sidetrack-owned `.m3u8` files in `Music/Sidetrack`, listed as
  `file:<id>` with `PlaylistActions.All` — create, add, remove, rename and delete. No write
  permission is needed because the app created those files. Each file carries a
  `#SIDETRACK-ID:` comment, so renaming keeps every open page and cached row pointing at the same
  playlist; every other player reads the file as a plain m3u8.
- **Isolation**: separate settings, queue, history and resume files, named for `device`.
- **Offline**: every item is a file on the phone, so the runtime reports online even in airplane
  mode; the UI must not offer "retry" for playback that never needed a network.

Not supported:

- **Radio** — there is no seed graph to walk. A local shuffle is not a station, and offering the
  flag would surface a page the adapter cannot fill.
- **Podcasts / shows** — MediaStore has no episode typing.
- **Follow artist** — nothing to follow.
- **Quality selection** — nothing transcodes; there is one original file.
- **Loudness normalization** — Media3 has no ReplayGain processor, so it needs a tag reader and a
  gain stage before the flag can be honest.
- **Gapless** — the shared Media3 engine restarts per item; the Spotify path is the only one that
  hands over seamlessly today.
- **Progress reporting and scrobbling** — there is no server to report to. Resume and history still
  work, because those are the app's own stores.
