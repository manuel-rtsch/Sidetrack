package io.github.manuelrtsch.sidetrack.demo

import io.github.manuelrtsch.sidetrack.Album
import io.github.manuelrtsch.sidetrack.Artist
import io.github.manuelrtsch.sidetrack.Episode
import io.github.manuelrtsch.sidetrack.Playlist
import io.github.manuelrtsch.sidetrack.R
import io.github.manuelrtsch.sidetrack.Show
import io.github.manuelrtsch.sidetrack.Track

/** Single playback source for every item. Never heard: the videos are muted. */
private const val STOCK_URI = "spotify:track:2GrbfjJz9M1kbplDKslSor"

private fun t(
    id: String,
    title: String,
    artist: String,
    artistId: String,
    albumId: String,
    art: Int,
    seconds: Int,
    explicit: Boolean = false
) = Track(
    id = id,
    title = title,
    artist = artist,
    artistId = artistId,
    albumId = albumId,
    artRes = art,
    durationMs = seconds * 1000L,
    explicit = explicit,
    playable = true,
    uri = STOCK_URI
)

private fun e(
    id: String,
    title: String,
    show: String,
    showId: String,
    published: String,
    art: Int,
    durationMs: Long
) = Episode(id, title, show, showId, published, art, durationMs, uri = STOCK_URI)

private val heroes = listOf(
    t("bw1", "Beauty and the Beast", "David Bowie", "bowie", "heroes", R.drawable.album_heroes, 212),
    t("bw2", "Joe the Lion", "David Bowie", "bowie", "heroes", R.drawable.album_heroes, 185),
    t("bw3", "Heroes", "David Bowie", "bowie", "heroes", R.drawable.album_heroes, 367),
    t("bw4", "Sons of the Silent Age", "David Bowie", "bowie", "heroes", R.drawable.album_heroes, 195),
    t("bw5", "V-2 Schneider", "David Bowie", "bowie", "heroes", R.drawable.album_heroes, 190),
    t("bw6", "Neuköln", "David Bowie", "bowie", "heroes", R.drawable.album_heroes, 274)
)

private val low = listOf(
    t("bl1", "Speed of Life", "David Bowie", "bowie", "low", R.drawable.album_low, 166),
    t("bl2", "Breaking Glass", "David Bowie", "bowie", "low", R.drawable.album_low, 111),
    t("bl3", "What in the World", "David Bowie", "bowie", "low", R.drawable.album_low, 143),
    t("bl4", "Sound and Vision", "David Bowie", "bowie", "low", R.drawable.album_low, 183),
    t("bl5", "Always Crashing in the Same Car", "David Bowie", "bowie", "low", R.drawable.album_low, 216),
    t("bl6", "Warszawa", "David Bowie", "bowie", "low", R.drawable.album_low, 377)
)

private val spell = listOf(
    t("ns1", "I Put a Spell on You", "Nina Simone", "nina", "spell", R.drawable.album_spell, 155),
    t("ns2", "Tomorrow Is My Turn", "Nina Simone", "nina", "spell", R.drawable.album_spell, 225),
    t("ns3", "Ne Me Quitte Pas", "Nina Simone", "nina", "spell", R.drawable.album_spell, 215),
    t("ns4", "Feeling Good", "Nina Simone", "nina", "spell", R.drawable.album_spell, 173),
    t("ns5", "Wild Is the Wind", "Nina Simone", "nina", "spell", R.drawable.album_spell, 206),
    t("ns6", "Don't Let Me Be Misunderstood", "Nina Simone", "nina", "spell", R.drawable.album_spell, 164)
)

private val mezzanine = listOf(
    t("ma1", "Angel", "Massive Attack", "massive", "mezzanine", R.drawable.album_mezzanine, 378),
    t("ma2", "Risingson", "Massive Attack", "massive", "mezzanine", R.drawable.album_mezzanine, 298),
    t("ma3", "Teardrop", "Massive Attack", "massive", "mezzanine", R.drawable.album_mezzanine, 329),
    t("ma4", "Inertia Creeps", "Massive Attack", "massive", "mezzanine", R.drawable.album_mezzanine, 356),
    t("ma5", "Exchange", "Massive Attack", "massive", "mezzanine", R.drawable.album_mezzanine, 251),
    t("ma6", "Man Next Door", "Massive Attack", "massive", "mezzanine", R.drawable.album_mezzanine, 356)
)

private val zombie = listOf(
    t("fk1", "Zombie", "Fela Kuti", "fela", "zombie", R.drawable.album_zombie, 745),
    t("fk2", "Mister Follow Follow", "Fela Kuti", "fela", "zombie", R.drawable.album_zombie, 103),
    t("fk3", "Observation No Crime", "Fela Kuti", "fela", "zombie", R.drawable.album_zombie, 495)
)

private val bvsc = listOf(
    t("bv1", "Chan Chan", "Buena Vista Social Club", "bvsc", "bvsc", R.drawable.album_bvsc, 256),
    t("bv2", "De Camino a la Vereda", "Buena Vista Social Club", "bvsc", "bvsc", R.drawable.album_bvsc, 300),
    t("bv3", "El Cuarto de Tula", "Buena Vista Social Club", "bvsc", "bvsc", R.drawable.album_bvsc, 448),
    t("bv4", "Dos Gardenias", "Buena Vista Social Club", "bvsc", "bvsc", R.drawable.album_bvsc, 184),
    t("bv5", "¿Y Tú Qué Has Hecho?", "Buena Vista Social Club", "bvsc", "bvsc", R.drawable.album_bvsc, 194),
    t("bv6", "Veinte Años", "Buena Vista Social Club", "bvsc", "bvsc", R.drawable.album_bvsc, 211)
)

private val artistList = listOf(
    Artist("bowie", "David Bowie", R.drawable.album_heroes, listOf("heroes", "low")),
    Artist("nina", "Nina Simone", R.drawable.album_spell, listOf("spell")),
    Artist("massive", "Massive Attack", R.drawable.album_mezzanine, listOf("mezzanine")),
    Artist("fela", "Fela Kuti", R.drawable.album_zombie, listOf("zombie")),
    Artist("bvsc", "Buena Vista Social Club", R.drawable.album_bvsc, listOf("bvsc")),
    // A sixth artist, credited through the two albums he co-wrote with Bowie.
    Artist("eno", "Brian Eno", R.drawable.album_low, listOf("low", "heroes"))
)

private val albumList = listOf(
    Album("heroes", "Heroes", "David Bowie", "bowie", 1977, R.drawable.album_heroes, heroes),
    Album("low", "Low", "David Bowie", "bowie", 1977, R.drawable.album_low, low),
    Album("spell", "I Put a Spell on You", "Nina Simone", "nina", 1965, R.drawable.album_spell, spell),
    Album("mezzanine", "Mezzanine", "Massive Attack", "massive", 1998, R.drawable.album_mezzanine, mezzanine),
    Album("zombie", "Zombie", "Fela Kuti", "fela", 1976, R.drawable.album_zombie, zombie),
    Album("bvsc", "Buena Vista Social Club", "Buena Vista Social Club", "bvsc", 1997, R.drawable.album_bvsc, bvsc)
)

private val playlistList = listOf(
    Playlist(
        "late_night",
        "Late Night Drive",
        "You",
        null,
        R.drawable.cover_late_night_drive,
        listOf(mezzanine[0], mezzanine[3], low[4], bvsc[4], heroes[5], mezzanine[2], spell[2], low[2])
    ),
    Playlist(
        "sunday_morning",
        "Sunday Morning",
        "You",
        null,
        R.drawable.cover_sunday_morning,
        listOf(bvsc[3], spell[1], heroes[0], low[3], bvsc[5], spell[4], heroes[4])
    ),
    Playlist(
        "focus",
        "Focus / No Words",
        "You",
        null,
        R.drawable.cover_focus_no_words,
        listOf(low[5], heroes[5], low[0], zombie[2], mezzanine[4], low[1], bvsc[2])
    ),
    Playlist(
        "kitchen_radio",
        "Kitchen Radio",
        "You",
        null,
        R.drawable.cover_kitchen_radio,
        listOf(bvsc[0], heroes[2], spell[3], zombie[0], mezzanine[2], heroes[1], bvsc[1])
    ),
    Playlist(
        "after_hours",
        "After Hours",
        "You",
        null,
        R.drawable.cover_after_hours,
        listOf(mezzanine[1], spell[2], low[1], zombie[1], heroes[3], bvsc[5])
    ),
    Playlist(
        "slow_sundays",
        "Slow Sundays",
        "You",
        null,
        R.drawable.cover_slow_sundays,
        listOf(bvsc[3], spell[0], heroes[4], low[2], mezzanine[4], bvsc[1])
    )
)

private val showList = listOf(
    Show(
        "bowie_life",
        "Bowie: A Life",
        "Northline Audio",
        R.drawable.cover_long_way_home,
        listOf(
            e("bi1", "The Boy from Brixton", "Bowie: A Life", "bowie_life", "12 Mar 2025", R.drawable.cover_long_way_home, 2_472_000L),
            e("bi2", "Ziggy and After", "Bowie: A Life", "bowie_life", "19 Mar 2025", R.drawable.cover_long_way_home, 2_334_000L),
            e("bi3", "Berlin, 1977", "Bowie: A Life", "bowie_life", "26 Mar 2025", R.drawable.cover_long_way_home, 2_646_000L),
            e("bi4", "The Thin White Duke", "Bowie: A Life", "bowie_life", "2 Apr 2025", R.drawable.cover_long_way_home, 2_367_000L),
            e("bi5", "Blackstar", "Bowie: A Life", "bowie_life", "9 Apr 2025", R.drawable.cover_long_way_home, 2_762_000L)
        )
    )
) + sharedShows(STOCK_URI)

private val REAL_LIBRARY = DemoLibrary(
    artists = artistList,
    albums = albumList,
    playlists = playlistList,
    shows = showList,
    liked = listOf(
        heroes[2], spell[0], spell[3], mezzanine[2], mezzanine[0],
        bvsc[0], bvsc[3], low[3], zombie[0], heroes[0]
    ),
    recents = listOf(
        heroes[3], mezzanine[2], spell[5], bvsc[1], low[1],
        zombie[1], heroes[5], mezzanine[4], bvsc[5]
    )
)

/**
 * The demo build's account: a fixed catalog of real, widely known songs that are not chart
 * material, so a promo video shows something recognisable instead of invented artists.
 *
 * Nothing here is streamed on purpose: every item plays [STOCK_URI] — a stock library-music track
 * (the one the previous pack used behind "Afterglow") — because the recordings are muted and the
 * audio is irrelevant. Artwork is rather more visible than sound, so each record carries its
 * official cover, bundled under `src/demo/res` and therefore shown instantly and offline. Only the
 * playlists and shows, which are still invented, use the generated art. [FictionalPack] remains the
 * fully fictional backup.
 *
 * The pinned search query in `flows/suite/02` and `flows/promo/02` is `bowie`, so the artist, his
 * two albums and the show below all match it and every search section is filled.
 */
object RealPack : PackedCatalog(REAL_LIBRARY)
