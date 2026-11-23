package io.github.manuelrtsch.sidetrack

/**
 * One unit of work a screen can ask for and then show as in flight.
 *
 * Keys exist so a request has one identity across the controller that starts it, the guard that
 * dedupes it and the screen that draws it. A per-feature boolean could only answer "is anything
 * loading"; this answers "is this loading", which is what the paged screens actually need.
 *
 * Keys are data classes where the work is per item, so two collections paging at once do not look
 * like one request, and objects where the app allows only one at a time.
 */
sealed interface TaskKey {
    data class Library(val filter: LibraryFilter) : TaskKey
    data class LibraryMore(val filter: LibraryFilter) : TaskKey
    data class Collection(val key: String) : TaskKey
    data class CollectionMore(val key: String) : TaskKey
    data class Artist(val id: String) : TaskKey
    data class ArtistMore(val id: String) : TaskKey

    /** A search request; the query is its identity, so a superseded term cannot claim the new one. */
    data class Search(val query: String) : TaskKey
    data class SearchMore(val scope: SearchScope) : TaskKey

    data class Radio(val seedId: String) : TaskKey
    data class ArtistRadio(val artistId: String) : TaskKey

    data object PlaylistChoices : TaskKey

    /** Sign-in has no item: one attempt at a time, superseded by the next press. */
    data object SignIn : TaskKey

    /** A write with no row of its own to show progress in, named by the action it performs. */
    data class Action(val name: String) : TaskKey
}
