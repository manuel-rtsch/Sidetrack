package io.github.manuelrtsch.sidetrack.data

import android.content.Context
import io.github.manuelrtsch.sidetrack.ServiceCapabilities
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.auth.LocalAuth
import io.github.manuelrtsch.sidetrack.auth.LocalServiceId
import java.io.File

/**
 * The music already on the device, as a signed-in service.
 *
 * Catalog and writer are [LocalCatalog]; the rest is the shared [Media3ServiceRuntime], so local
 * playback gets the same media session, notification, audio focus, queue, history and resume point
 * as every other Media3-backed service. Nothing here talks to a network: [isOnline] is true because
 * a file needs none, and the queue/history/resume files are the provider's own, so a device library
 * does not mix with the last server account.
 */
class LocalRuntime(
    context: Context,
    val auth: LocalAuth = LocalAuth(context.applicationContext),
    files: LocalPlaylistFiles = MediaStorePlaylistFiles(context.applicationContext),
    source: () -> LocalLibrarySource = { MediaStoreLibrarySource(context.applicationContext) }
) : Media3ServiceRuntime(
    context = context,
    service = LocalServiceId,
    capabilities = ServiceCapabilities.Device,
    auth = auth,
    stores = StoreNames.forService(LocalServiceId.value)
) {
    /** Every item is a file on this phone, so an unreachable network changes nothing. */
    override val isOnline: Boolean get() = true

    override val catalog: MediaCatalog = LocalCatalog(
        source = source,
        files = files,
        favorites = FileLocalFavorites(File(appContext.filesDir, LIKED_FILE))
    )

    private companion object {
        const val LIKED_FILE = "sidetrack-device-liked.txt"
    }
}

/**
 * The device library as a registered service.
 *
 * Sign-in is a permission grant ([SignInKind.Device]), so the sign-in screen has no fields to
 * render. Identity and label come from the auth object and the core constant, so the picker and the
 * running session cannot disagree about what the user selected.
 */
class LocalProvider(private val session: LocalRuntime) : MusicServiceProvider {
    override val id: ServiceId = LocalServiceId
    override val displayName: String = LocalAuth.DISPLAY_NAME
    override val capabilities: ServiceCapabilities = ServiceCapabilities.Device
    override val signInKind: SignInKind = SignInKind.Device

    override fun createAuth(): ServiceAuth = session.auth
}
