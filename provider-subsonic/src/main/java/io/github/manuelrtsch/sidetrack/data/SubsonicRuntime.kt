package io.github.manuelrtsch.sidetrack.data

import android.content.Context
import io.github.manuelrtsch.sidetrack.ServiceCapabilities
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.auth.SubsonicAuth
import io.github.manuelrtsch.sidetrack.auth.SubsonicCredentialStore

/**
 * A signed-in account on one member of the Subsonic family.
 *
 * Catalog, auth and writer are the shared Subsonic adapter; [family] only supplies the identity,
 * label and store names, so Navidrome, Nextcloud Music and Subsonic itself all run the same code.
 * Playback is the shared [Media3ServiceRuntime] lifecycle, so the media session, notification and
 * audio focus behave exactly as they do for Jellyfin and Plex. Queue, history and resume files are
 * per family entry, so switching services never mixes two accounts.
 */
class SubsonicRuntime(
    context: Context,
    val family: SubsonicFamily = SubsonicFamily.Navidrome,
    val auth: SubsonicAuth = SubsonicAuth(
        SubsonicCredentialStore(context, family.credentialStoreName),
        family
    )
) : Media3ServiceRuntime(
    context = context,
    service = family.id,
    capabilities = ServiceCapabilities.Subsonic,
    auth = auth,
    stores = StoreNames(
        settings = family.settingsStoreName,
        queue = family.localStoreName("queue"),
        history = family.localStoreName("history"),
        resume = family.localStoreName("resume")
    )
) {
    override val catalog: MediaCatalog = SubsonicCatalog(
        family = family,
        api = { auth.api() },
        settings = { settingsState.value }
    )
}

/**
 * One family member as a registered service, including the auth object [AppSession] asks for after
 * sign-in. Identity and label come from [family], so the picker and the running session cannot
 * disagree about what the user selected.
 */
class SubsonicProvider(
    private val session: SubsonicRuntime,
    private val family: SubsonicFamily = session.family
) : MusicServiceProvider {
    override val id: ServiceId = family.id
    override val displayName: String = family.displayName
    override val capabilities: ServiceCapabilities = ServiceCapabilities.Subsonic
    override val signInKind: SignInKind = SignInKind.Password(family.passwordDefaults)

    override fun createAuth(): ServiceAuth = session.auth
}
