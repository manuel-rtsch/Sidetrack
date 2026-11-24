package io.github.manuelrtsch.sidetrack

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import hrln.interactive.sidekit.design.input.KeypadModule
import hrln.interactive.sidekit.design.input.LocalKeypad
import hrln.interactive.sidekit.sdk.input.SharedPreferencesKeypadPreferences
import io.github.manuelrtsch.sidetrack.auth.LocalAudioPermission
import io.github.manuelrtsch.sidetrack.data.AppSessions
import io.github.manuelrtsch.sidetrack.data.KnownServices
import io.github.manuelrtsch.sidetrack.data.SignInKind
import kotlinx.coroutines.launch

internal fun NavGraphBuilder.settingsGraph(
    nav: NavHostController,
    hostBacked: Boolean,
    onKeypadBackOverride: ((() -> Boolean)?) -> Unit = {}
) {
    composable(SETTINGS) { entry ->
        val vm = entry.sidetrackViewModel(nav)
        val account by vm.accountState.collectAsStateWithLifecycle()
        val chrome by vm.chromeState.collectAsStateWithLifecycle()
        val playback by vm.playbackState.collectAsStateWithLifecycle()
        val writes by vm.writesState.collectAsStateWithLifecycle()
        val context = LocalContext.current
        val appSession = remember(context) { AppSessions.get(context) }
        // The cache row is the only reader of the size, so the directory walk starts when this
        // screen opens instead of during ViewModel creation.
        LaunchedEffect(Unit) { vm.refreshCacheSize() }
        SettingsScreen(
            account,
            chrome,
            playback.recent.size,
            serviceName = appSession.serviceName,
            capabilities = vm.capabilities,
            clearingCache = writes.isRunning(TaskKey.Action.Kind.ClearCache),
            onBack = { nav.popBackStack() },
            onAudioQuality = { nav.navigate(AUDIO_QUALITY) },
            onNormalization = vm::toggleNormalization,
            onGapless = vm::toggleGapless,
            onAutoplay = vm::toggleAutoplay,
            onClearCache = vm::clearCache,
            onClearHistory = vm::clearHistory,
            onUndoClearHistory = vm::undoClearHistory,
            onSignOut = {
                vm.signOut()
                // Signing out is the only route back to the picker: choosing a service is a
                // provisioning step, not a setting, so it must not happen behind a live app.
                nav.navigate(SERVICE_PICKER) {
                    popUpTo(ROOT) { inclusive = false }
                    launchSingleTop = true
                }
            },
            onDismissNotice = vm::dismissNotice,
            // A host-backed Sidekit owns the theme and the keypad, so the Keypad row is hidden
            // rather than offering edits the host would ignore. Appearance stays: the host owns
            // the case colour and compact layout inside it, but not Sidetrack's own row density
            // or its Now Playing backdrop, and the screen drops the host-owned rows itself.
            onAppearance = { nav.navigate(APPEARANCE) },
            onKeyboard = if (hostBacked) null else ({ nav.navigate(KEYBOARD) }),
            // The Help route is the same Welcome body: it keeps its Back action and returns here
            // instead of clearing the onboarding flag on the way out.
            onTutorial = { nav.navigate(HELP) },
            onAbout = { nav.navigate(ABOUT) }
        )
    }
    composable(SERVICE_PICKER) {
        val context = LocalContext.current
        val appSession = remember(context) { AppSessions.get(context) }
        // The picker is the first screen on a fresh install and a pushed screen when it comes
        // from Sign in. Either way it is the same neutral screen: no pre-selection, and the
        // same two bar actions (About, Help) rather than a back button.
        ServicePickerScreen(
            services = KnownServices,
            onAbout = { nav.navigate(ABOUT) },
            onHelp = { nav.navigate(HELP) },
            onSelect = { id ->
                if (!AppSessions.select(context, id)) {
                    false
                } else {
                    // A service that still holds credentials goes straight in; a new one starts
                    // at sign-in, exactly as it would on the next launch.
                    val next = if (appSession.signedIn.value) HOME else SIGN_IN
                    nav.navigate(next) {
                        popUpTo(ROOT) { inclusive = false }
                        launchSingleTop = true
                    }
                    true
                }
            }
        )
    }
    composable(AUDIO_QUALITY) { entry ->
        val vm = entry.sidetrackViewModel(nav)
        val account by vm.accountState.collectAsStateWithLifecycle()
        val chrome by vm.chromeState.collectAsStateWithLifecycle()
        AudioQualityScreen(
            account,
            chrome,
            onBack = { nav.popBackStack() },
            backLabel = settingsBackLabel(nav),
            onSelect = vm::setAudioQuality,
            onDismissNotice = vm::dismissNotice
        )
    }
    composable(APPEARANCE) {
        SidetrackAppearanceScreen(
            hostBacked = hostBacked,
            backLabel = settingsBackLabel(nav),
            onBack = { nav.popBackStack() }
        )
    }
    composable(KEYBOARD) {
        SidetrackKeyboardScreen(
            onBack = { nav.popBackStack() },
            backLabel = settingsBackLabel(nav),
            keypadAccessAvailable = !hostBacked,
            onOpenModulePicker = { nav.navigate(KEYPAD_MODULE) }
        )
    }
    composable(KEYPAD_MODULE) {
        val context = LocalContext.current
        val keypad = LocalKeypad.current
        val keypadPrefs = remember(context) {
            SharedPreferencesKeypadPreferences(context, SidetrackLocalSettings.KEYPAD_FILE)
        }
        SidetrackKeypadModuleScreen(
            module = keypad?.mapper?.customModule ?: keypad?.module ?: keypadPrefs.module() ?: KeypadModule.T9,
            onBack = { nav.popBackStack() },
            onSelect = { module -> selectKeypadModule(keypad, keypadPrefs, module) },
            backLabel = keypadModuleBackLabel(nav)
        )
    }
    composable(ABOUT) {
        val backLabel = when (nav.previousBackStackEntry?.destination?.route) {
            SIGN_IN -> "Sign in"
            ONBOARDING, HELP -> "Welcome"
            else -> "Settings"
        }
        SidetrackAboutScreen(onBack = { nav.popBackStack() }, backLabel = backLabel)
    }
    composable(ONBOARDING) {
        onboardingPages(
            nav = nav,
            hostBacked = hostBacked,
            onKeypadBackOverride = onKeypadBackOverride,
            onDone = {
                nav.navigate(HOME) {
                    // Replace setup (and anything still below it, such as Sign in), so Back from
                    // Home leaves Sidetrack instead of replaying the pages. The flag is written
                    // by the screen before this runs.
                    popUpTo(ROOT) { inclusive = false }
                    launchSingleTop = true
                }
            }
        )
    }
    composable(HELP) {
        // The same pages the first run shows, opened before a service is chosen: they teach the
        // keypad, so they are the most useful thing a first-run user can read here.
        onboardingPages(
            nav = nav,
            hostBacked = hostBacked,
            onKeypadBackOverride = onKeypadBackOverride,
            onDone = { nav.popBackStack() },
            onBack = { nav.popBackStack() }
        )
    }
    composable(SIGN_IN) { entry ->
        val vm = entry.sidetrackViewModel(nav)
        val account by vm.accountState.collectAsStateWithLifecycle()
        val chrome by vm.chromeState.collectAsStateWithLifecycle()
        val context = LocalContext.current
        LaunchedEffect(account.signedIn) {
            if (account.signedIn) {
                val next = if (SidetrackLocalSettings(context).needsOnboarding()) ONBOARDING else HOME
                nav.navigate(next) {
                    popUpTo(ROOT) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }
        val appSession = AppSessions.get(context)
        // The provider describes its own sign-in: a password form with optional public demo
        // defaults, or a browser/custom-tab flow with no fields. No shared code names a service.
        val signInKind = appSession.provider?.signInKind
        val passwordAuth = signInKind is SignInKind.Password
        val deviceAuth = signInKind is SignInKind.Device
        val defaults = (signInKind as? SignInKind.Password)?.defaults?.let {
            PasswordSignInDefaults(it.serverUrl, it.username, it.password)
        }
        // A device library signs in by asking for the media permission. The result is handed to
        // the provider either way: it checks the grant itself and names what is missing.
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.signInDevice() }
        SignInScreen(
            chrome,
            onBack = {
                // Usually the picker is the step behind. A signed-out launch of a provisioned
                // install starts here with nothing behind, so Back opens the picker instead of
                // being a dead end — the same answer, reached without a state to check.
                if (!nav.popBackStack()) {
                    nav.navigate(SERVICE_PICKER) { launchSingleTop = true }
                }
            },
            onSignIn = vm::signIn,
            onDismissNotice = vm::dismissNotice,
            serviceName = appSession.serviceName.ifEmpty { "your music service" },
            passwordAuth = passwordAuth,
            defaults = defaults,
            deviceAuth = deviceAuth,
            onDeviceSignIn = { permission.launch(LocalAudioPermission) },
            onPasswordSignIn = vm::signIn,
            pending = account.authPending
        )
    }
}

/**
 * The setup pages, reached two ways: as the first run (which replaces the back stack) and from
 * Help (which goes back). Both need the signed-in state to know whether the quality page is
 * reachable, and both must set the keypad back override, so they share one body.
 */
@Composable
private fun onboardingPages(
    nav: NavHostController,
    hostBacked: Boolean,
    onKeypadBackOverride: ((() -> Boolean)?) -> Unit,
    onDone: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val appSession = remember(context) { AppSessions.get(context) }
    val signedIn by appSession.signedIn.collectAsStateWithLifecycle()
    SidetrackOnboardingScreen(
        hostBacked = hostBacked,
        onAbout = { nav.navigate(ABOUT) },
        onKeyboard = { nav.navigate(KEYBOARD) },
        onAppearance = { nav.navigate(APPEARANCE) },
        onAudioQuality = { nav.navigate(AUDIO_QUALITY) },
        canSetQuality = signedIn &&
            appSession.runtimeOrNull?.capabilities?.supportsQualitySelection == true,
        onDone = onDone,
        onBack = onBack,
        onKeypadBackOverride = onKeypadBackOverride
    )
}

/** Back returns to the page that opened a settings screen, not always to Settings. */
private fun settingsBackLabel(nav: NavHostController): String = if (nav.previousBackStackEntry?.destination?.route in setOf(ONBOARDING, HELP)) {
    "Welcome"
} else {
    "Settings"
}

/** The module picker is reached from setup and from the keypad settings page. */
private fun keypadModuleBackLabel(nav: NavHostController): String = when (nav.previousBackStackEntry?.destination?.route) {
    ONBOARDING, HELP -> "Welcome"
    KEYBOARD -> "Keypad"
    else -> "Settings"
}
