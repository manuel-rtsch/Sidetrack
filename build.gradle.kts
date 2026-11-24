plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.spotless) apply false
}

val ktlintRulesFromEditorConfig: Map<String, String> =
    providers.fileContents(rootProject.layout.projectDirectory.file(".editorconfig"))
        .asText
        .map { text ->
            text.lines()
                .map { it.substringBefore('#').trim() }
                .filter { '=' in it }
                .map { it.split('=', limit = 2).map(String::trim) }
                .filter { (key, _) -> key == "max_line_length" || key.startsWith("ktlint_") }
                .associate { (key, value) -> key to value }
        }
        .get()

allprojects {
    apply(plugin = "com.diffplug.spotless")

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        // Deliberately no ratchetFrom: a ratchet only sees files changed since its base, so a file
        // that drifted after it was pushed was never checked again. With the tree clean, checking all
        // 374 Kotlin files costs a few seconds and catches drift that a ratchet cannot.
        kotlin {
            target("src/**/*.kt")
            ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintRulesFromEditorConfig)
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintRulesFromEditorConfig)
        }
    }
}

// `-PsidetrackSkipLintVital=true` keeps the fatal-only lint pass out of the CI jobs whose only claim
// is that a variant assembles. `lintVital` re-analyses sources the `android-lint` job already covers
// with the full `lintDebug`/`lintDemo`, and it was the most expensive task on the critical path.
// Nothing here disables lint: released builds never pass the flag, so `assembleRelease` still fails
// on a fatal issue, and `lintDebug`/`lintDemo` still run on every push.
if (providers.gradleProperty("sidetrackSkipLintVital").isPresent) {
    allprojects {
        tasks.matching { it.name.startsWith("lintVital") }.configureEach { enabled = false }
    }
}

// One entry point for the coverage gate. `:app` is the merging module, so its rule is the
// product-wide floor; the other four verify their own modules. Add `-PsidetrackScreenshots`
// for the full suite, which has the higher floor.
tasks.register("coverage") {
    group = "verification"
    description = "Runs the unit tests and verifies every coverage floor."
    dependsOn(
        ":app:koverVerifyDebug",
        ":app:koverHtmlReportDebug",
        ":app:koverXmlReportDebug",
        ":core:koverVerifyDebug",
        ":provider-local:koverVerifyDebug",
        ":provider-spotify:koverVerifyDebug",
        ":provider-jellyfin:koverVerifyDebug",
        ":provider-subsonic:koverVerifyDebug",
        ":provider-plex:koverVerifyDebug"
    )
}

// ---------------------------------------------------------------------------
// Copy/paste detection
//
// `./gradlew cpd` writes build/reports/cpd/cpd{Kotlin,Rust}.{csv,md}. `./gradlew cpdCheck` fails when
// the duplicated line count grows past the budget in gradle.properties. PMD is installed under
// tools/pmd instead of being resolved from Maven, so a PMD or rule change cannot silently move the
// baseline that the duplication section of TODO.md records.
// ---------------------------------------------------------------------------

// Bump the version only together with a re-recorded baseline: a new PMD rewrites every count.
val cpdPmdVersion = "7.29.0"
val cpdPmdSha256 = "d27177c185b837fa57f097e482d1253468aa035379c5eb2671fa0a279109b179"

// Ignoring identifiers and literals makes a hit a structural clone rather than two functions that
// happen to share a shape. 80 tokens is deliberately low: the budget, not the threshold, is what
// keeps the backlog from growing.
val cpdMinimumTokens = 80

// `native/vendor` holds the vendored librespot crates and is never scanned; only the bridge that
// this repo actually maintains is.
val cpdKotlinSources = listOf(
    "app",
    "core",
    "provider-local",
    "provider-plex",
    "provider-spotify",
    "provider-subsonic",
    "provider-jellyfin"
).map { fileTree("$it/src") { include("**/*.kt") } }

val cpdRustSources = fileTree("native/sidetrack-spotify") { include("src/**/*.rs") }

val installPmd = tasks.register<InstallPmd>("installPmd") {
    group = "verification"
    description = "Downloads and unpacks the pinned PMD distribution into tools/pmd."
    version.set(cpdPmdVersion)
    sha256.set(cpdPmdSha256)
    home.set(layout.projectDirectory.dir("tools/pmd/pmd-bin-$cpdPmdVersion"))
    archive.set(layout.projectDirectory.file("tools/pmd/pmd-dist-$cpdPmdVersion-bin.zip"))
    marker.set(layout.projectDirectory.file("tools/pmd/.pmd-$cpdPmdVersion.installed"))
}

// Keyed on the marker rather than the 142 MB of jars: `installPmd` is what makes it change.
val pmdTool = fileTree(layout.projectDirectory.dir("tools/pmd/pmd-bin-$cpdPmdVersion")) {
    include("lib/*.jar")
}

fun cpdTask(name: String, language: String, sources: List<FileTree>, description: String) = tasks.register<CpdAnalysis>(name) {
    group = "verification"
    this.description = description
    dependsOn(installPmd)
    this.sources.from(sources)
    this.language.set(language)
    minimumTokens.set(cpdMinimumTokens)
    relativizeRoot.set(rootDir.absolutePath)
    tool.from(pmdTool)
    fileList.set(layout.buildDirectory.file("cpd/$name.files"))
    csvReport.set(layout.buildDirectory.file("reports/cpd/$name.csv"))
    markdownReport.set(layout.buildDirectory.file("reports/cpd/$name.md"))
}

val cpdKotlin = cpdTask(
    "cpdKotlin",
    "kotlin",
    cpdKotlinSources,
    "Finds duplicated Kotlin across the app, core and provider modules."
)

val cpdRust = cpdTask(
    "cpdRust",
    "rust",
    listOf(cpdRustSources),
    "Finds duplicated Rust in the Spotify native bridge."
)

tasks.register("cpd") {
    group = "verification"
    description = "Writes the copy/paste reports to build/reports/cpd without failing the build."
    dependsOn(cpdKotlin, cpdRust)
}

tasks.register<CpdBudget>("cpdCheck") {
    group = "verification"
    description = "Fails when duplication exceeds sidetrack.cpd.maxDuplicateLines."
    reports.from(cpdKotlin.flatMap { it.csvReport }, cpdRust.flatMap { it.csvReport })
    maximumDuplicateLines.set(
        providers.gradleProperty("sidetrack.cpd.maxDuplicateLines").map(String::toInt).orElse(824)
    )
}
