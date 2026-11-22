package io.github.manuelrtsch.sidetrack.auth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.data.AuthInput
import io.github.manuelrtsch.sidetrack.data.RuntimeAuth
import io.github.manuelrtsch.sidetrack.data.ServiceAccount
import io.github.manuelrtsch.sidetrack.data.ServiceException
import io.github.manuelrtsch.sidetrack.data.ServiceFailure

/** The id this provider publishes itself under; the picker, stores and queue all key off it. */
val LocalServiceId: ServiceId = ServiceId.Device

/**
 * The runtime permission the device library needs.
 *
 * Android 13 split the broad storage permission into per-type ones; the audio one is all this
 * provider reads. Below 33 the legacy storage permission still covers it.
 */
val LocalAudioPermission: String
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

/**
 * Signing in to the device library is granting one permission — there is no account, no server and
 * no secret to keep.
 *
 * Nothing is stored: the platform already remembers the grant across launches, and the signed-out
 * flag only lasts for this process. That is why [clear] does not revoke anything — signing out is
 * "stop using this library now", the way the other providers stop using an account.
 */
class LocalAuth(private val context: Context) : RuntimeAuth {

    @Volatile
    private var signedOut = false

    fun hasAccess(): Boolean = ContextCompat.checkSelfPermission(context, LocalAudioPermission) == PackageManager.PERMISSION_GRANTED

    override fun hasCredentials(): Boolean = !signedOut && hasAccess()

    override suspend fun signIn(input: AuthInput): ServiceAccount {
        if (input !is AuthInput.Device) {
            throw ServiceException(ServiceFailure.NotConnected, "This library needs access to your music, not an account")
        }
        if (!hasAccess()) {
            throw ServiceException(ServiceFailure.NotConnected, "Allow access to your music to use this library")
        }
        signedOut = false
        return account()
    }

    override suspend fun restore(): ServiceAccount? = account().takeIf { hasCredentials() }

    override suspend fun signOut() = clear()

    override fun clear() {
        signedOut = true
    }

    private fun account() = ServiceAccount(
        service = LocalServiceId,
        userId = "device",
        displayName = DISPLAY_NAME
    )

    companion object {
        const val DISPLAY_NAME = "Local library"
    }
}
