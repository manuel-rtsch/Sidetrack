package io.github.manuelrtsch.sidetrack

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import hrln.interactive.sidekit.design.components.SidekitCard
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sidetrack.ui.InlineLinkArrowSize
import io.github.manuelrtsch.sidetrack.ui.LinkArrow

/** Where Sidetrack's source lives; the author row's "Source code" opens it in a browser. */
internal const val SOURCE_CODE_URL = "https://github.com/manuel-rtsch/Sidetrack"

/** Sidetrack builds against a fork of Sidekit; the Sidekit card opens the fork. */
internal const val SIDEKIT_URL = "https://github.com/manuel-rtsch/Sidekit"

/** The Author card opens the author's GitHub profile. */
internal const val AUTHOR_URL = "https://github.com/manuel-rtsch"

/**
 * Hands a page to whatever browser the device has. A ROM without one leaves the press with
 * nothing to do — there is no notice bar on the About page and nothing to undo — so the missing
 * activity is swallowed rather than crashing the app the user is reading about it in.
 */
internal fun openPage(context: Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: ActivityNotFoundException) {
        // No browser installed.
    }
}

internal fun openSourceCode(context: Context) = openPage(context, SOURCE_CODE_URL)

private data class Licence(val software: String, val licence: String)

/** The git-describe tail Gradle puts behind the tag, plus the build-type suffixes. */
private val buildSuffix = Regex("""-\d+-g[0-9a-f]+(?:-dirty)?$""")

/**
 * The tag part of the Gradle version name: `0.3.5-11-g55e317f-dirty-dev` becomes `0.3.5`.
 * The About page shows this, so the screenshot does not change with every build; the commit
 * count and hash are revealed only when the version row is clicked.
 */
internal fun plainVersionName(versionName: String): String = versionName
    .removeSuffix("-dev")
    .removeSuffix("-demo")
    .replace(buildSuffix, "")
    .removeSuffix("-dirty")
    .ifBlank { versionName }

/**
 * Which Spotify path this APK was built for. The native bridge needs a Rust toolchain and the
 * NDK at build time, so building without it is legitimate for local iteration — but such a build
 * falls back to the public Web API, which rate-limits the desktop client ID and stalls. The
 * version row names the engine so a tester's bug report does not have to be guessed at.
 */
internal val spotifyEngineLabel: String
    get() = if (BuildConfig.NATIVE_SPOTIFY) {
        "Native Spotify engine"
    } else {
        "Spotify Web API, no native bridge"
    }

private val licences = listOf(
    Licence("Sidetrack", "MIT"),
    Licence("Sidekit fork (Harlen Postill)", "MIT"),
    Licence("librespot", "MIT"),
    Licence("Jellyfin SDK for Kotlin", "LGPL-3.0"),
    Licence("AndroidX / Jetpack Compose", "Apache 2.0"),
    Licence("Kotlin", "Apache 2.0")
)

/**
 * Version, author, the Sidekit SDK Sidetrack is built on, and the licences of everything it
 * ships. Reached from Settings rather than crowding the settings list itself.
 */
@Composable
fun SidetrackAboutScreen(onBack: () -> Unit, backLabel: String = "Settings") {
    val context = LocalContext.current
    val packageInfo = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0) }
    val versionName = packageInfo.versionName.orEmpty()
    val versionCode = packageInfo.longVersionCode
    val listState = rememberLazyListState()
    // Rows and link cards are the page's focus stops, so Sidekit's list scrolls by chasing focus
    // just as it does on Settings. The first row takes focus on entry.
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    // The build number stays out of the screenshot: the plain tag is enough until it is asked
    // for, and a commit-count build number would change the image on every build.
    var showBuild by rememberSaveable { mutableStateOf(false) }

    SidekitScaffold(
        title = "About",
        actions = sidetrackBack(backLabel, onBack)
    ) { padding ->
        SidekitList(padding, state = listState) {
            // The conditional build line shifts the fade slot, so count rows as they register.
            var rowIndex = 0
            fun LazyListScope.row(key: String, content: @Composable (Modifier) -> Unit) {
                val index = rowIndex++
                item(key = key) { content(Modifier.listFade(listState, index)) }
            }

            row("version") { modifier ->
                SidetrackItem(
                    "Version",
                    modifier.focusRequester(first),
                    secondary = "Sidetrack ${plainVersionName(versionName)}",
                    secondaryToEnd = true,
                    onClick = { showBuild = !showBuild }
                )
            }
            if (showBuild) {
                row("build") { modifier ->
                    SidekitText(
                        "Build $versionCode · $spotifyEngineLabel",
                        modifier.padding(horizontal = 18.dp),
                        style = SidekitTheme.typography.caption,
                        color = SidekitTheme.colors.surfaceA6,
                        maxLines = Int.MAX_VALUE
                    )
                }
            }
            row("source") { modifier ->
                SidetrackItem(
                    "Source code",
                    modifier,
                    showLinkArrow = true,
                    onClick = { openSourceCode(context) }
                )
            }
            row("author") { modifier ->
                AboutCard(
                    "Author",
                    modifier,
                    onClick = { openPage(context, AUTHOR_URL) }
                ) {
                    SidekitText(
                        "Manuel Rottschäfer",
                        style = SidekitTheme.typography.label,
                        maxLines = Int.MAX_VALUE
                    )
                    SidekitText(
                        "github.com/manuel-rtsch",
                        style = SidekitTheme.typography.caption,
                        color = SidekitTheme.colors.surfaceA6,
                        maxLines = Int.MAX_VALUE
                    )
                }
            }
            row("sidekit") { modifier ->
                AboutCard(
                    "Built with Sidekit",
                    modifier,
                    onClick = { openPage(context, SIDEKIT_URL) }
                ) {
                    SidekitText(
                        // 1.12.0 is this fork's version, not Harlen Postill's upstream release.
                        "Sidekit SDK ${BuildConfig.SIDEKIT_VERSION}",
                        style = SidekitTheme.typography.label,
                        maxLines = Int.MAX_VALUE
                    )
                    SidekitText(
                        "Fork of Harlen Postill's Sidekit",
                        style = SidekitTheme.typography.label,
                        maxLines = Int.MAX_VALUE
                    )
                    SidekitText(
                        "github.com/manuel-rtsch/Sidekit",
                        style = SidekitTheme.typography.caption,
                        color = SidekitTheme.colors.surfaceA6,
                        maxLines = Int.MAX_VALUE
                    )
                }
            }
            row("licenses") { modifier ->
                // The table is a reading stop, not an action: it takes D-pad focus so the list
                // scrolls the whole card under the title, but there is nothing for OK to open.
                SidekitCard(modifier.fillMaxWidth().focusable()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SidekitText("Licenses", style = SidekitTheme.typography.headline)
                        licences.forEach { entry ->
                            LicenceRow(entry)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The About page's header-subtitle box: a headline over the lines that describe it. [onClick]
 * makes the whole card the link, so the card is also the row's keypad focus stop.
 */
@Composable
private fun AboutCard(
    title: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    body: @Composable ColumnScope.() -> Unit
) {
    SidekitCard(modifier.fillMaxWidth(), onClick = onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SidekitText(title, Modifier.weight(1f), style = SidekitTheme.typography.headline)
                // The card opens a page, so it carries the same arrow the link rows do.
                LinkArrow(size = InlineLinkArrowSize, tint = SidekitTheme.colors.surfaceA6)
            }
            body()
        }
    }
}

/**
 * One table row: the software name on the left, its licence right-aligned. Both sit on the
 * baseline at the top of the row, so a wrapped name does not push its licence down the table.
 */
@Composable
private fun LicenceRow(entry: Licence) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SidekitText(
            entry.software,
            Modifier.weight(1f),
            style = SidekitTheme.typography.label,
            maxLines = Int.MAX_VALUE
        )
        SidekitText(
            entry.licence,
            style = SidekitTheme.typography.label,
            color = SidekitTheme.colors.surfaceA6,
            textAlign = TextAlign.End,
            maxLines = Int.MAX_VALUE
        )
    }
}
