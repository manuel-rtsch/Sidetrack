package io.github.manuelrtsch.sidetrack

import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import hrln.interactive.sidekit.design.action.LocalChrome
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitCompactItem
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
 * One-time welcome; the same body doubles as the picker's Help page and keeps the onboarding flag
 * unwritten so the real welcome still happens once a service is chosen. It follows Sidekit's
 * re-detection while open, so a keypad guessed wrong can be corrected without leaving the page.
 */
@Composable
fun SidetrackOnboardingScreen(
    hostBacked: Boolean,
    onAbout: () -> Unit,
    onKeyboard: () -> Unit,
    onDone: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current.applicationContext
    val local = remember(context) { SidetrackLocalSettings(context) }
    val keypad = LocalKeypad.current
    val keypadPrefs = remember(context) {
        SharedPreferencesKeypadPreferences(context, SidetrackLocalSettings.KEYPAD_FILE)
    }
    val custom = keypad?.mapper?.customModule ?: keypadPrefs.customModule()
    var accessRevision by remember { mutableIntStateOf(0) }
    val keypadBackEnabled = remember(accessRevision) { SidetrackKeypadAccess.isEnabled(context) }
    // Android does not report an accessibility-service change through a callback. Refresh when
    // the user comes back from the Keyboard screen or the system Accessibility screen.
    LifecycleResumeEffect(Unit) {
        accessRevision++
        onPauseOrDispose {}
    }
    // The attached module is the truth and it can change while this screen is open: swapping the
    // module, or pressing a key Sidekit has not seen yet, re-detects it (Keypad.observe). Reading
    // it here is what makes the page follow along without being reopened.
    val attached = keypad?.module ?: custom ?: keypadPrefs.module() ?: KeypadModule.T9
    // A manual cycle holds only until the next key reveals a different module, so the page can
    // never settle on a keypad that is not the one attached.
    var manual by remember { mutableStateOf<KeypadModule?>(null) }
    LaunchedEffect(attached) { if (manual != null && manual != attached) manual = null }
    val module = manual ?: attached
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
    SidetrackOnboardingContent(
        module = module,
        customMapping = custom != null,
        canOpenKeyboard = !hostBacked,
        onOpenKeyboard = onKeyboard,
        onCycleKeyboard = {
            val next = nextKeypadModule(module)
            manual = next
            when {
                // Without a keypad there is nothing to re-map, so the choice is only remembered.
                keypad == null -> keypadPrefs.writeModule(next)

                // A custom mapping fixes the module by design: cycling it is choosing which module
                // the attached keys are read as, which is exactly what useCustomMapping does.
                keypad.mapper.customModule != null -> keypad.useCustomMapping(next)

                // A choice, not a detection: Sidekit keeps it while the same keypad reports itself,
                // so the D-pad press that follows does not undo it.
                else -> keypad.chooseModule(next)
            }
        },
        canManageKeypadAccess = !hostBacked,
        keypadBackEnabled = keypadBackEnabled,
        onAbout = onAbout,
        onBack = onBack,
        onDone = {
            local.markOnboardingSeen()
            onDone()
        }
    )
}

/**
 * Presentational body, kept separate so the screenshot suite can draw a deterministic keypad
 * without a real Sidekit host.
 */
@Composable
internal fun SidetrackOnboardingContent(
    module: KeypadModule,
    customMapping: Boolean = false,
    canOpenKeyboard: Boolean = true,
    onOpenKeyboard: () -> Unit = {},
    onCycleKeyboard: () -> Unit = {},
    canManageKeypadAccess: Boolean = false,
    keypadBackEnabled: Boolean = false,
    onAbout: () -> Unit,
    onDone: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    val list = rememberLazyListState()
    val keys = rememberWelcomeKeys(module)
    val optionsOpen = LocalChrome.current?.optionsOpen == true

    SidekitScaffold(
        title = "Welcome",
        actions = listOf(
            // Help mode returns to the picker; the first run keeps its single way forward.
            if (onBack != null) {
                SideAction.back("back", "Back", onBack)
            } else {
                SideAction.forward("getStarted", "Get started", onDone)
            }
        ),
        // A real extra action, so pressing Options here demonstrates what the card explains.
        options = sidetrackOptions(aboutOption(onAbout))
    ) { padding ->
        SidetrackKeypadWelcomeList(
            padding = padding,
            list = list,
            modifier = keys.modifier,
            module = module,
            customMapping = customMapping,
            canOpenKeyboard = canOpenKeyboard,
            onOpenKeyboard = onOpenKeyboard,
            onCycleKeyboard = onCycleKeyboard,
            canManageKeypadAccess = canManageKeypadAccess,
            keypadBackEnabled = keypadBackEnabled,
            pressed = keys.pressed,
            optionsOpen = optionsOpen
        )
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
    val focus = remember { FocusRequester() }
    var pressed by remember { mutableStateOf<KeypadHabit?>(null) }
    // Compose only delivers key events to a focused node, and this screen starts with none — move
    // focus so the first press is not lost to the platform.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(pressed) {
        if (pressed == null) return@LaunchedEffect
        delay(1_500L)
        pressed = null
    }
    val modifier = Modifier
        .focusRequester(focus)
        .focusable()
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (native.action == KeyEvent.ACTION_DOWN) {
                keypadHabitForKey(module, native.keyCode)?.let { pressed = it }
            }
            false
        }
    return WelcomeKeys(pressed, modifier)
}

@Composable
private fun SidetrackKeypadWelcomeList(
    padding: PaddingValues,
    list: LazyListState,
    modifier: Modifier,
    module: KeypadModule,
    customMapping: Boolean,
    canOpenKeyboard: Boolean,
    onOpenKeyboard: () -> Unit,
    onCycleKeyboard: () -> Unit,
    canManageKeypadAccess: Boolean,
    keypadBackEnabled: Boolean,
    pressed: KeypadHabit?,
    optionsOpen: Boolean
) {
    // A list only pads its rows a few pixels apart, which reads as one column. The page's groups
    // get a wider break so the selector, the card and the guide each read as their own block.
    val section = Modifier.padding(top = SidekitTheme.spacing.pageGap)
    SidekitList(padding, modifier = modifier, state = list) {
        // The selector opens the page: on a first run the module in front of the user is the first
        // thing to agree on, and on a host-backed install this is the only place to change it.
        item(key = "keyboard") {
            SidekitCompactItem("Select keyboard", secondary = module.label, onClick = onCycleKeyboard)
        }
        item(key = "intro") {
            SidekitText(
                "Learn your keypad.",
                section.padding(horizontal = 18.dp),
                style = SidekitTheme.typography.caption,
                maxLines = 1
            )
        }
        item(key = "essentials") {
            KeypadEssentials(
                module,
                modifier = section,
                customMapping = customMapping,
                pressed = pressed,
                optionsOpen = optionsOpen
            )
        }
        item(key = "tryIt") {
            SidekitText(
                "Try it: press Options now — About Sidetrack is there.",
                Modifier.padding(horizontal = 18.dp),
                style = SidekitTheme.typography.small,
                color = SidekitTheme.colors.surfaceA6,
                maxLines = 2
            )
        }
        item(key = "guideTitle") {
            SidekitText(
                "Where those keys are",
                section.padding(horizontal = 18.dp),
                style = SidekitTheme.typography.caption,
                maxLines = 1
            )
        }
        item(key = "guide") { KeypadGuide(module) }
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
        if (canOpenKeyboard) {
            item(key = "keyboardSettings") {
                val backStatus = when {
                    !canManageKeypadAccess -> null
                    keypadBackEnabled -> "Back on"
                    else -> "Back off"
                }
                SidekitCompactItem(
                    "Keyboard settings",
                    modifier = section,
                    secondary = listOfNotNull("Input, mapping, guide", backStatus).joinToString(" · ") + " ›",
                    onClick = onOpenKeyboard
                )
            }
        }
    }
}
