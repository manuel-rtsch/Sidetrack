package io.github.manuelrtsch.sidetrack.demo

import io.github.manuelrtsch.sidetrack.Album
import io.github.manuelrtsch.sidetrack.Artist
import io.github.manuelrtsch.sidetrack.Episode
import io.github.manuelrtsch.sidetrack.Playlist
import io.github.manuelrtsch.sidetrack.R
import io.github.manuelrtsch.sidetrack.Show
import io.github.manuelrtsch.sidetrack.Track

/**
 * Real tracks used only as playback sources, with their real durations so the progress bar and
 * the seek target stay truthful. Album covers, titles and artists are never taken from them.
 */
private val donors: List<Pair<String, Long>> = listOf(
    "spotify:track:2GrbfjJz9M1kbplDKslSor" to 177_000L, // Allston Night Owl
    "spotify:track:3ehAmD8XuuiMtsbCZ11KLO" to 170_000L, // OneEightFour
    "spotify:track:42aLGgbb3mTnaOQlmTENQK" to 166_000L, // Copley Beat
    "spotify:track:3fqWXWJ87N4LT1672uckKV" to 133_000L, // Three Stories
    "spotify:track:4gsLnkfjfUh2SOS0oBAxVs" to 179_000L, // Boston Landing
    "spotify:track:1qWNN5RES6RU4MYsc94GUJ" to 138_000L, // Elmore Heights
    "spotify:track:78BDfJsWGCN1cLtm2GI2AG" to 146_000L, // Greylock
    "spotify:track:5JCk3XTYSDi64wQny585ZG" to 159_000L, // Kid Kodi
    "spotify:track:3HxvvXFOubtLQNfRdOuQBT" to 184_000L, // Pedalrider
    "spotify:track:0oVkdajWjheO7lEyjQfH2P" to 185_000L, // Walking Shoes
    "spotify:track:1W9iPwwvMKgfIabAhxLmgl" to 134_000L, // Rodney Skopes
    "spotify:track:1oukfqeqx27nKVOy7a6fsU" to 158_000L // The Yards
)

/** An eight-hour ambient track: an episode's real length must outlast any recording. */
private const val EPISODE_DONOR = "spotify:track:6NohJPZI27aOchSS6uWXQm"

private var nextDonor = 0

private fun donor(): Pair<String, Long> = donors[nextDonor++ % donors.size].also {
    if (nextDonor == Int.MAX_VALUE) nextDonor = 0
}

private fun t(
    id: String,
    title: String,
    artist: String,
    artistId: String,
    albumId: String,
    art: Int,
    explicit: Boolean = false
): Track {
    val (uri, durationMs) = donor()
    return Track(
        id = id,
        title = title,
        artist = artist,
        artistId = artistId,
        albumId = albumId,
        artRes = art,
        durationMs = durationMs,
        explicit = explicit,
        playable = true,
        uri = uri
    )
}

private fun e(
    id: String,
    title: String,
    show: String,
    showId: String,
    published: String,
    art: Int,
    durationMs: Long
) = Episode(id, title, show, showId, published, art, durationMs, uri = EPISODE_DONOR)

private val nightSignals = listOf(
    t("ns1", "Afterglow", TRIO, "aurora", "night_signals", R.drawable.cover_night_signals),
    t("ns2", "Static Bloom", TRIO, "aurora", "night_signals", R.drawable.cover_night_signals),
    t("ns3", "Midnight Radio", TRIO, "aurora", "night_signals", R.drawable.cover_night_signals),
    t("ns4", "Paper Moon", TRIO, "aurora", "night_signals", R.drawable.cover_night_signals),
    t("ns5", "Low Tide", TRIO, "aurora", "night_signals", R.drawable.cover_night_signals, explicit = true),
    t("ns6", "Neon Rooms", TRIO, "aurora", "night_signals", R.drawable.cover_night_signals),
    t("ns7", "Slow Motion", TRIO, "aurora", "night_signals", R.drawable.cover_night_signals),
    t("ns8", "Satellite", TRIO, "aurora", "night_signals", R.drawable.cover_night_signals),
    t("ns9", "Wide Awake", TRIO, "aurora", "night_signals", R.drawable.cover_night_signals),
    t("ns10", "Last Light", TRIO, "aurora", "night_signals", R.drawable.cover_night_signals)
)

private val glassHours = listOf(
    t("gh1", "Glass Hours", TRIO, "aurora", "glass_hours", R.drawable.cover_glass_hours),
    t("gh2", "Amber", TRIO, "aurora", "glass_hours", R.drawable.cover_glass_hours),
    t("gh3", "Held Open", TRIO, "aurora", "glass_hours", R.drawable.cover_glass_hours),
    t("gh4", "The Quiet Part", TRIO, "aurora", "glass_hours", R.drawable.cover_glass_hours),
    t("gh5", "Half a Mile", TRIO, "aurora", "glass_hours", R.drawable.cover_glass_hours),
    t("gh6", "Window Seat", TRIO, "aurora", "glass_hours", R.drawable.cover_glass_hours),
    t("gh7", "Soft Landing", TRIO, "aurora", "glass_hours", R.drawable.cover_glass_hours),
    t("gh8", "Glass Hours (Reprise)", TRIO, "aurora", "glass_hours", R.drawable.cover_glass_hours)
)

private val coastline = listOf(
    t("co1", "Coastline", "Mira Sol", "mira", "coastline", R.drawable.cover_coastline),
    t("co2", "Saltwater", "Mira Sol", "mira", "coastline", R.drawable.cover_coastline),
    t("co3", "Blue Hour", "Mira Sol", "mira", "coastline", R.drawable.cover_coastline),
    t("co4", "North Wind", "Mira Sol", "mira", "coastline", R.drawable.cover_coastline),
    t("co5", "Open Water", "Mira Sol", "mira", "coastline", R.drawable.cover_coastline),
    t("co6", "Every Harbour", "Mira Sol", "mira", "coastline", R.drawable.cover_coastline),
    t("co7", "Drift", "Mira Sol", "mira", "coastline", R.drawable.cover_coastline),
    t("co8", "Coming Home", "Mira Sol", "mira", "coastline", R.drawable.cover_coastline)
)

private val roomsWeLeft = listOf(
    t("rw1", "Rooms We Left", "The Static Coast", "static", "rooms_we_left", R.drawable.cover_rooms_we_left),
    t("rw2", "Hallway Light", "The Static Coast", "static", "rooms_we_left", R.drawable.cover_rooms_we_left),
    t("rw3", "Furniture", "The Static Coast", "static", "rooms_we_left", R.drawable.cover_rooms_we_left, explicit = true),
    t("rw4", "A Door, Ajar", "The Static Coast", "static", "rooms_we_left", R.drawable.cover_rooms_we_left),
    t("rw5", "Everything We Kept", "The Static Coast", "static", "rooms_we_left", R.drawable.cover_rooms_we_left),
    t("rw6", "Keys", "The Static Coast", "static", "rooms_we_left", R.drawable.cover_rooms_we_left),
    t("rw7", "After the Lease", "The Static Coast", "static", "rooms_we_left", R.drawable.cover_rooms_we_left),
    t("rw8", "Empty House", "The Static Coast", "static", "rooms_we_left", R.drawable.cover_rooms_we_left)
)

private val goldenHour = listOf(
    t("go1", "Golden Hour", "Ivy & Ash", "ivy", "golden_hour", R.drawable.cover_golden_hour),
    t("go2", "Marigold", "Ivy & Ash", "ivy", "golden_hour", R.drawable.cover_golden_hour),
    t("go3", "Long Shadows", "Ivy & Ash", "ivy", "golden_hour", R.drawable.cover_golden_hour),
    t("go4", "Honey", "Ivy & Ash", "ivy", "golden_hour", R.drawable.cover_golden_hour),
    t("go5", "Porch Song", "Ivy & Ash", "ivy", "golden_hour", R.drawable.cover_golden_hour),
    t("go6", "Warm Front", "Ivy & Ash", "ivy", "golden_hour", R.drawable.cover_golden_hour),
    t("go7", "Before the Streetlights", "Ivy & Ash", "ivy", "golden_hour", R.drawable.cover_golden_hour),
    t("go8", "Still Daylight", "Ivy & Ash", "ivy", "golden_hour", R.drawable.cover_golden_hour)
)

private val softFocus = listOf(
    t("sf1", "Soft Focus", "Lumen Park", "lumen", "soft_focus", R.drawable.cover_soft_focus),
    t("sf2", "Pastel Static", "Lumen Park", "lumen", "soft_focus", R.drawable.cover_soft_focus),
    t("sf3", "Lavender", "Lumen Park", "lumen", "soft_focus", R.drawable.cover_soft_focus),
    t("sf4", "Low Resolution", "Lumen Park", "lumen", "soft_focus", R.drawable.cover_soft_focus),
    t("sf5", "Silver Lining", "Lumen Park", "lumen", "soft_focus", R.drawable.cover_soft_focus),
    t("sf6", "A Little Blur", "Lumen Park", "lumen", "soft_focus", R.drawable.cover_soft_focus),
    t("sf7", "Daylight Savings", "Lumen Park", "lumen", "soft_focus", R.drawable.cover_soft_focus),
    t("sf8", "Out of Frame", "Lumen Park", "lumen", "soft_focus", R.drawable.cover_soft_focus)
)

/** The sixth artist and album, so every library category has more than one screen's worth. */
private val northlight = listOf(
    t("nl1", "Cold Front", "Northlight", "northlight", "northlight", R.drawable.cover_northlight),
    t("nl2", "Signal Fire", "Northlight", "northlight", "northlight", R.drawable.cover_northlight),
    t("nl3", "Low Beam", "Northlight", "northlight", "northlight", R.drawable.cover_northlight),
    t("nl4", "First Light", "Northlight", "northlight", "northlight", R.drawable.cover_northlight)
)

private val artistList = listOf(
    Artist("aurora", TRIO, R.drawable.cover_aurora_vale, listOf("night_signals", "glass_hours")),
    Artist("mira", "Mira Sol", R.drawable.cover_mira_sol, listOf("coastline")),
    Artist("static", "The Static Coast", R.drawable.cover_static_coast, listOf("rooms_we_left")),
    Artist("ivy", "Ivy & Ash", R.drawable.cover_ivy_ash, listOf("golden_hour")),
    Artist("lumen", "Lumen Park", R.drawable.cover_lumen_park, listOf("soft_focus")),
    Artist("northlight", "Northlight", R.drawable.cover_northlight, listOf("northlight"))
)

private val albumList = listOf(
    Album("night_signals", "Night Signals", TRIO, "aurora", 2024, R.drawable.cover_night_signals, nightSignals),
    Album("glass_hours", "Glass Hours", TRIO, "aurora", 2022, R.drawable.cover_glass_hours, glassHours),
    Album("coastline", "Coastline", "Mira Sol", "mira", 2023, R.drawable.cover_coastline, coastline),
    Album("rooms_we_left", "Rooms We Left", "The Static Coast", "static", 2024, R.drawable.cover_rooms_we_left, roomsWeLeft),
    Album("golden_hour", "Golden Hour", "Ivy & Ash", "ivy", 2021, R.drawable.cover_golden_hour, goldenHour),
    Album("soft_focus", "Soft Focus", "Lumen Park", "lumen", 2025, R.drawable.cover_soft_focus, softFocus),
    Album("northlight", "Northlight", "Northlight", "northlight", 2025, R.drawable.cover_northlight, northlight)
)

private val playlistList = listOf(
    Playlist(
        "late_night",
        "Late Night Drive",
        "You",
        null,
        R.drawable.cover_late_night_drive,
        listOf(nightSignals[0], nightSignals[5], coastline[2], roomsWeLeft[1], softFocus[3], nightSignals[7], glassHours[5], coastline[6])
    ),
    Playlist(
        "sunday_morning",
        "Sunday Morning",
        "You",
        null,
        R.drawable.cover_sunday_morning,
        listOf(goldenHour[0], goldenHour[3], glassHours[1], coastline[0], goldenHour[6], softFocus[4], glassHours[6])
    ),
    Playlist(
        "focus",
        "Focus / No Words",
        "You",
        null,
        R.drawable.cover_focus_no_words,
        listOf(softFocus[0], softFocus[1], softFocus[5], glassHours[7], coastline[6], roomsWeLeft[5], nightSignals[3])
    ),
    Playlist(
        "kitchen_radio",
        "Kitchen Radio",
        "You",
        null,
        R.drawable.cover_kitchen_radio,
        listOf(goldenHour[1], coastline[1], nightSignals[8], goldenHour[4], glassHours[2], coastline[4], roomsWeLeft[0])
    ),
    Playlist(
        "after_hours",
        "After Hours",
        "You",
        null,
        R.drawable.cover_after_hours,
        listOf(nightSignals[1], softFocus[6], roomsWeLeft[3], coastline[5], glassHours[4], goldenHour[7])
    ),
    Playlist(
        "slow_sundays",
        "Slow Sundays",
        "You",
        null,
        R.drawable.cover_slow_sundays,
        listOf(goldenHour[2], coastline[3], softFocus[5], nightSignals[6], roomsWeLeft[6], northlight[2])
    )
)

private val showList = listOf(
    Show(
        "long_way_home",
        "Bowie Vale Sessions",
        "Northline Audio",
        R.drawable.cover_long_way_home,
        listOf(
            e("lw1", "The Road Out of Town", "Bowie Vale Sessions", "long_way_home", "12 Mar 2025", R.drawable.cover_long_way_home, 2_442_000L),
            e("lw2", "A Room with One Window", "Bowie Vale Sessions", "long_way_home", "19 Mar 2025", R.drawable.cover_long_way_home, 2_118_000L),
            e("lw3", "The Ferry at Dusk", "Bowie Vale Sessions", "long_way_home", "26 Mar 2025", R.drawable.cover_long_way_home, 2_676_000L),
            e("lw4", "Notes from the Coast Road", "Bowie Vale Sessions", "long_way_home", "2 Apr 2025", R.drawable.cover_long_way_home, 2_307_000L),
            e("lw5", "The Last Bus", "Bowie Vale Sessions", "long_way_home", "9 Apr 2025", R.drawable.cover_long_way_home, 2_529_000L)
        )
    )
) + sharedShows(EPISODE_DONOR)

private val FICTIONAL_LIBRARY = DemoLibrary(
    artists = artistList,
    albums = albumList,
    playlists = playlistList,
    shows = showList,
    liked = listOf(
        nightSignals[0], nightSignals[3], coastline[0], coastline[2], roomsWeLeft[1],
        goldenHour[0], goldenHour[5], softFocus[2], glassHours[3], nightSignals[7]
    ),
    recents = listOf(
        nightSignals[0], coastline[2], roomsWeLeft[1], goldenHour[0], softFocus[2],
        nightSignals[5], glassHours[1], coastline[6], roomsWeLeft[5]
    )
)

/**
 * The demo build's backup content set: fully fictional, with a real Spotify URI per item.
 *
 * [RealPack] is served by default; `-PdemoPack=fictional` builds this one instead. It needs no
 * real titles, so it stays useful when the real pack is re-picked or a video must not name any
 * artist. Playback still runs through librespot with the signed-in account, so every URI is a
 * real, playable track: recordings are muted, and the URIs keep the transport, seek and
 * end-of-track paths the production ones.
 *
 * The screenshot suite's device-build fixture, deliberately neutral — no personal library, no
 * real title in the video. The recorded search is pinned to "bowie", so the invented artist and
 * a show carry that word and all four search sections appear.
 */
object FictionalPack : PackedCatalog(FICTIONAL_LIBRARY)

/** The artist that carries the pinned search query. */
private const val TRIO = "Bowie Vale"
