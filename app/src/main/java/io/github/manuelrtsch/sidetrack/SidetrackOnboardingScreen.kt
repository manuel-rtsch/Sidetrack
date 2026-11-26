package io.github.manuelrtsch.sidetrack

import android.content.Intent
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.LocalChrome
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.input.Keypad
import hrln.interactive.sidekit.design.input.KeypadModule
import hrln.interactive.sidekit.design.input.LocalKeypad
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.sdk.input.SharedPreferencesKeypadPreferences
import hrln.interactive.sidekit.sdk.settings.KeypadGuide
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The three setup pages, in order: the keypad, the guide that answers what its keys do, then the
 * few personal choices.
 */
private const val ONBOARDING_STEPS = 3
private const val STEP_KEYPAD = 0
private const val STEP_GUIDE = 1
private const val STEP_PERSONALIZE = 2

/**
 * The first-run setup, after sign-in: three short pages — the keypad, its guide, then the handful
 * of choices worth making before Home. The same body doubles as the picker's Help page and Settings' Tutorial:
 * there [onBack] is set, so it starts at the first page, never reads or writes the stored step, and
 * closes instead of marking setup complete.
 *
 * It follows Sidekit's re-detection while open, so a keypad guessed wrong can be corrected without
 * leaving the page.
 */
@Composable
fun SidetrackOnboardingScreen(
    hostBacked: Boolean,
    onAbout: () -> Unit,
    onKeyboard: () -> Unit,
    onDone: () -> Unit,
    onAppearance: () -> Unit = {},
    onAudioQuality: () -> Unit = {},
    canSetQuality: Boolean = false,
    onBack: (() -> Unit)? = null,
    onKeypadBackOverride: ((() -> Boolean)?) -> Unit = {}
) {
    val context = LocalContext.current.applicationContext
    val local = remember(context) { SidetrackLocalSettings(context) }
    val keypad = LocalKeypad.current
    val keypadPrefs = remember(context) {
        SharedPreferencesKeypadPreferences(context, SidetrackLocalSettings.KEYPAD_FILE)
    }
    val custom = keypad?.mapper?.customModule ?: keypadPrefs.customModule()
    // The attached module is the truth and it can change while this screen is open: swapping the
    // module, or pressing a key Sidekit has not seen yet, re-detects it (Keypad.observe). Reading
    // it here is what makes the page follow along without being reopened.
    val attached = keypad?.module ?: custom ?: keypadPrefs.module() ?: KeypadModule.T9
    // A manual cycle holds only until the next key reveals a different module, so the page can
    // never settle on a keypad that is not the one attached.
    var manual by remember { mutableStateOf<KeypadModule?>(null) }
    LaunchedEffect(attached) { if (manual != null && manual != attached) manual = null }
    val module = manual ?: attached

    // A first run resumes where it left off; Help always starts at the first page.
    val firstRun = onBack == null
    var step by rememberSaveable(firstRun) {
        mutableStateOf(
            if (firstRun) local.onboardingStep.coerceIn(0, ONBOARDING_STEPS - 1) else 0
        )
    }
    LaunchedEffect(firstRun, step) {
        if (firstRun) local.onboardingStep = step
    }
    // The module picker is a page inside setup, not a step: it never changes Step X of 3.
    var selectingKeypad by rememberSaveable { mutableStateOf(false) }

    // The keypad Back key closes the picker, then steps back through the flow. At the first page
    // the override is released: Help then pops its route, and a first run leaves the app as before.
    DisposableEffect(step, selectingKeypad, onKeypadBackOverride) {
        when {
            selectingKeypad -> onKeypadBackOverride {
                selectingKeypad = false
                true
            }

            step > STEP_KEYPAD -> onKeypadBackOverride {
                if (step > STEP_KEYPAD) {
                    step -= 1
                    true
                } else {
                    false
                }
            }

            else -> onKeypadBackOverride(null)
        }
        onDispose { onKeypadBackOverride(null) }
    }
    BackHandler(enabled = selectingKeypad || step > STEP_KEYPAD) {
        if (selectingKeypad) selectingKeypad = false else step -= 1
    }

    val openKeypadAccess: () -> Unit = remember(context) {
        {
            runCatching {
                context.startActivity(
                    SidetrackKeypadAccess.settingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
    // The grant lives in Android's accessibility settings, so the row has to re-read it when the
    // user comes back rather than remember the answer from when the page opened.
    var accessRevision by remember { mutableStateOf(0) }
    val keypadBackEnabled = remember(context, accessRevision) {
        SidetrackKeypadAccess.isEnabled(context)
    }
    LifecycleResumeEffect(Unit) {
        accessRevision++
        onPauseOrDispose {}
    }

    val finish: () -> Unit = {
        if (firstRun) local.markOnboardingSeen()
        onDone()
    }

    LaunchedEffect(keypad) {
        SidetrackDebug.log {
            "welcome keypad=${keypad != null} module=${keypad?.module} custom=${keypad?.mapper?.customModule}"
        }
        val attachedKeypad = keypad ?: return@LaunchedEffect
        snapshotFlow { attachedKeypad.module to attachedKeypad.mapper.customModule }
            .distinctUntilChanged()
            .collect { (shown, customModule) ->
                SidetrackDebug.log("welcome module=$shown custom=$customModule")
            }
    }

    if (selectingKeypad) {
        SidetrackKeypadModuleScreen(
            module = manual ?: custom ?: attached,
            onBack = { selectingKeypad = false },
            onSelect = { next ->
                manual = next
                selectKeypadModule(keypad, keypadPrefs, next)
                selectingKeypad = false
            }
        )
        return
    }

    SidetrackOnboardingContent(
        step = step,
        module = module,
        keypadLabel = custom?.let { "${keypadModuleLabel(it)} · custom" } ?: keypadModuleLabel(module),
        customMapping = custom != null,
        showKeypadBack = !hostBacked,
        keypadBackEnabled = keypadBackEnabled,
        canSetQuality = canSetQuality,
        onOpenKeyboard = onKeyboard,
        onOpenKeypadModule = { selectingKeypad = true },
        onOpenKeypadAccess = openKeypadAccess,
        onAppearance = onAppearance,
        onAudioQuality = onAudioQuality,
        onAbout = onAbout,
        onStepBack = { step = (step - 1).coerceAtLeast(STEP_KEYPAD) },
        onStepForward = { step = (step + 1).coerceAtMost(STEP_PERSONALIZE) },
        onDone = finish,
        onBack = onBack,
        onSkip = if (firstRun) finish else null
    )
}

/**
 * Presentational body, kept separate so the screenshot suite can draw a deterministic keypad
 * without a real Sidekit host. The step is a value, so both pages can be photographed.
 */
@Composable
internal fun SidetrackOnboardingContent(
    module: KeypadModule,
    step: Int = STEP_KEYPAD,
    keypadLabel: String = keypadModuleLabel(module),
    customMapping: Boolean = false,
    showKeypadBack: Boolean = false,
    keypadBackEnabled: Boolean = false,
    canSetQuality: Boolean = false,
    onOpenKeyboard: () -> Unit = {},
    onOpenKeypadModule: () -> Unit = {},
    onOpenKeypadAccess: () -> Unit = {},
    onAppearance: () -> Unit = {},
    onAudioQuality: () -> Unit = {},
    onStepBack: () -> Unit = {},
    onStepForward: () -> Unit = {},
    onAbout: () -> Unit,
    onDone: () -> Unit,
    onBack: (() -> Unit)? = null,
    onSkip: (() -> Unit)? = null
) {
    val firstRun = onBack == null
    val last = step >= STEP_PERSONALIZE
    val keys = rememberWelcomeKeys(module)
    val optionsOpen = LocalChrome.current?.optionsOpen == true
    val actions = buildList {
        if (step > STEP_KEYPAD) {
            add(SideAction.back("back", "Back", onStepBack))
        } else if (onBack != null) {
            add(SideAction.back("back", "Back", onBack))
        }
        if (last) {
            add(SideAction.forward("done", if (firstRun) "Get started" else "Done", onDone))
        } else {
            add(SideAction.forward("continue", "Continue", onStepForward))
        }
    }

    SidekitScaffold(
        title = when (step) {
            STEP_PERSONALIZE -> "Make it yours"
            STEP_GUIDE -> "Keypad guide"
            else -> "Welcome"
        },
        actions = actions,
        options = sidetrackOptions(
            aboutOption(onAbout),
            onSkip?.let {
                SideAction("skipSetup", "Skip setup", tone = ActionTone.Muted, onClick = it)
            }
        )
    ) { padding ->
        when (step) {
            STEP_PERSONALIZE -> MakeItYoursPage(
                padding = padding,
                canSetQuality = canSetQuality,
                onAppearance = onAppearance,
                onAudioQuality = onAudioQuality
            )

            STEP_GUIDE -> KeypadGuidePage(
                padding = padding,
                module = module,
                customMapping = customMapping
            )

            else -> KeypadSetupPage(
                padding = padding,
                modifier = keys.modifier,
                module = module,
                keypadLabel = keypadLabel,
                customMapping = customMapping,
                showKeypadBack = showKeypadBack,
                keypadBackEnabled = keypadBackEnabled,
                onOpenKeyboard = onOpenKeyboard,
                onOpenKeypadModule = onOpenKeypadModule,
                onOpenKeypadAccess = onOpenKeypadAccess,
                pressed = keys.pressed,
                optionsOpen = optionsOpen
            )
        }
    }
}

/**
 * Watching the keys the page receives stops it teaching silently — an unanswered press reads as
 * "not recognised" — and lets Sidekit re-detect the module from them. The modifier never consumes
 * an event, so every key keeps its normal job.
 */
private class WelcomeKeys(val pressed: KeypadHabit?, val modifier: Modifier)

@Composable
private fun rememberWelcomeKeys(module: KeypadModule): WelcomeKeys {
    var pressed by remember { mutableStateOf<KeypadHabit?>(null) }
    LaunchedEffect(pressed) {
        if (pressed == null) return@LaunchedEffect
        delay(1_500L)
        pressed = null
    }
    // Preview events travel from the focused row through this ancestor, so the list can watch the
    // keys without stealing focus from the row D-pad navigation needs.
    val modifier = Modifier.onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (native.action == KeyEvent.ACTION_DOWN) {
            keypadHabitForKey(module, native.keyCode)?.let { pressed = it }
        }
        false
    }
    return WelcomeKeys(pressed, modifier)
}

@Composable
private fun SetupProgress(step: Int) {
    SidekitText(
        "Step ${step + 1} of $ONBOARDING_STEPS",
        Modifier.padding(horizontal = 18.dp, vertical = SidekitTheme.spacing.pageGap),
        style = SidekitTheme.typography.caption,
        color = SidekitTheme.colors.surfaceA6,
        maxLines = 1
    )
}

@Composable
private fun KeypadSetupPage(
    padding: PaddingValues,
    modifier: Modifier,
    module: KeypadModule,
    keypadLabel: String,
    customMapping: Boolean,
    showKeypadBack: Boolean,
    keypadBackEnabled: Boolean,
    onOpenKeyboard: () -> Unit,
    onOpenKeypadModule: () -> Unit,
    onOpenKeypadAccess: () -> Unit,
    pressed: KeypadHabit?,
    optionsOpen: Boolean
) {
    val list = rememberLazyListState()
    // A list only pads its rows a few pixels apart, which reads as one column. The page's groups
    // get a wider break so the selector and the card each read as their own block.
    val section = Modifier.padding(top = SidekitTheme.spacing.pageGap)
    // Start on the first actionable row, as the other keypad screens do. The list itself is not a
    // focus target: focus has to sit on a row so D-pad moves through the page instead of stopping
    // on the container.
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    SidekitList(padding, modifier = modifier, state = list) {
        item(key = "progress") { SetupProgress(STEP_KEYPAD) }
        // The selector opens the picker: on a first run the module in front of the user is the
        // first thing to agree on, and on a host-backed install this is the only place to change
        // it. The row stays a link so setup does not grow a fourth mini-page inline.
        item(key = "keypad") {
            SidetrackItem(
                "Keypad",
                Modifier.focusRequester(first),
                secondary = keypadLabel,
                secondaryToEnd = true,
                showLinkArrow = true,
                showLinkArrowWithSecondary = true,
                onClick = onOpenKeypadModule
            )
        }
        if (showKeypadBack) {
            item(key = "keypadBack") {
                SidetrackItem(
                    "Back key",
                    secondary = if (keypadBackEnabled) "On" else "Off · Enable",
                    secondaryToEnd = true,
                    onClick = onOpenKeypadAccess
                )
            }
        }
        item(key = "essentials") {
            KeypadEssentials(
                module,
                modifier = section,
                customMapping = customMapping,
                pressed = pressed,
                optionsOpen = optionsOpen,
                // The card is a focus stop like the rows around it; selecting it opens the full
                // keypad page instead of leaving a dead press on an informational surface.
                onClick = onOpenKeyboard
            )
        }
    }
}

/**
 * The interactive guide: every key of the attached module, and tapping one answers what that key
 * does on a tap and on a hold. It sits between choosing the layout and the personal choices so the
 * labels taught on the first page have somewhere to be looked up before setup ends.
 */
@Composable
private fun KeypadGuidePage(
    padding: PaddingValues,
    module: KeypadModule,
    customMapping: Boolean
) {
    val list = rememberLazyListState()
    SidekitList(padding, state = list) {
        item(key = "progress") { SetupProgress(STEP_GUIDE) }
        item(key = "intro") {
            SidekitText(
                "Tap a key to see what it does on a tap and a hold.",
                Modifier.padding(horizontal = 18.dp),
                style = SidekitTheme.typography.caption,
                color = SidekitTheme.colors.surfaceA6,
                maxLines = 2
            )
        }
        if (customMapping) {
            item(key = "guideCustom") {
                SidekitText(
                    "A custom mapping is active; the guide shows the module’s default labels.",
                    Modifier.padding(horizontal = 18.dp),
                    style = SidekitTheme.typography.tiny,
                    color = SidekitTheme.colors.surfaceA6,
                    maxLines = 2
                )
            }
        }
        item(key = "guide") { KeypadGuide(module) }
    }
}

@Composable
private fun MakeItYoursPage(
    padding: PaddingValues,
    canSetQuality: Boolean,
    onAppearance: () -> Unit,
    onAudioQuality: () -> Unit
) {
    val list = rememberLazyListState()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    SidekitList(padding, state = list) {
        item(key = "progress") { SetupProgress(STEP_PERSONALIZE) }
        item(key = "intro") {
            SidekitText(
                "Change these later in Settings.",
                Modifier.padding(start = 18.dp, end = 18.dp, bottom = SidekitTheme.spacing.pageGap),
                style = SidekitTheme.typography.caption,
                color = SidekitTheme.colors.surfaceA6,
                maxLines = 1
            )
        }
        item(key = "appearance") {
            SidetrackItem(
                "Appearance",
                Modifier.focusRequester(first),
                showLinkArrow = true,
                showLinkArrowWithSecondary = true,
                onClick = onAppearance
            )
        }
        if (canSetQuality) {
            item(key = "quality") {
                SidetrackItem(
                    "Audio quality",
                    showLinkArrow = true,
                    showLinkArrowWithSecondary = true,
                    onClick = onAudioQuality
                )
            }
        }
    }
}
