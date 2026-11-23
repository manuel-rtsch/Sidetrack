package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.bridge.SpotifyNative

/**
 * A [SpotifyNative] that answers nothing useful: no audio, no catalog, no event, every command
 * accepted.
 *
 * Each test fake overrides only the entry points it is about, so it says which part of the bridge
 * it models instead of repeating thirty overrides of `null` and `true`. The defaults are the ones a
 * fake of the sink, the gateway, the session or the catalog agrees on: a connected, available
 * bridge whose player has not been created yet, and whose catalog calls answer "unavailable".
 */
open class SilentSpotifyNative : SpotifyNative {
    override val isAvailable: Boolean get() = true

    override fun initialize() = true
    override fun setTmpDir(path: String) = Unit
    override fun setCacheDir(path: String, sizeLimitBytes: Long) = Unit
    override fun connect(accessToken: String): String? = null
    override fun setAccessToken(accessToken: String) = true
    override fun disconnect() = Unit
    override fun hasPlayer() = false
    override fun createPlayer(bitrateKbps: Int, normalization: Boolean, gapless: Boolean) = true
    override fun recreatePlayer(bitrateKbps: Int, normalization: Boolean, gapless: Boolean) = true
    override fun preload(uri: String) = true
    override fun load(uri: String, positionMs: Long, autoplay: Boolean) = true
    override fun play() = true
    override fun pause() = true
    override fun stop() = true
    override fun seek(positionMs: Long) = true
    override fun readAudio(timeoutMs: Long): ByteArray? = null
    override fun drainAudio() = Unit
    override fun audioFormat(): String? = null
    override fun pollEvent(): String? = null

    override fun catalogLibrary(filter: String, offset: Long, limit: Long): String? = null
    override fun catalogCollection(kind: String, id: String, offset: Long, limit: Long): String? = null
    override fun catalogArtist(id: String): String? = null
    override fun catalogAlbumsBy(id: String, offset: Long, limit: Long): String? = null
    override fun catalogTopTracks(id: String, offset: Long, limit: Long): String? = null
    override fun catalogSongRadio(id: String, limit: Long): String? = null
    override fun catalogArtistRadio(id: String, limit: Long): String? = null
    override fun catalogSearch(query: String, scopes: String, offset: Long, limit: Long): String? = null

    override fun collectionContains(set: String, uris: String): String? = null
    override fun collectionWrite(set: String, uris: String, removed: Boolean) = true
    override fun playlistSaved(id: String): String? = null
    override fun playlistSave(id: String, save: Boolean) = true
    override fun playlistItemsAdd(id: String, uris: String): String? = null
    override fun playlistItemsRemove(id: String, uris: String): String? = "ok"
}
