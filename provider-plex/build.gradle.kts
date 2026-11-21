import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kover)
}

// The version this client reports to its server; see buildSrc/GitVersion.kt.
val clientVersionName = GitVersion.nameFrom(project)

// `*IntegrationTest` classes need a live server; opt in with -PsidetrackIntegration.
val runIntegrationTests = providers.gradleProperty("sidetrackIntegration").isPresent

android {
    // Unique per module; :app owns io.github.manuelrtsch.sidetrack. Sources keep their packages.
    namespace = "io.github.manuelrtsch.sidetrack.plex"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 31
        // PlexAuth reports the client version to plex.tv through BuildConfig.VERSION_NAME. The
        // library plugin does not drive versionName, so mirror the app's git-derived version.
        buildConfigField("String", "VERSION_NAME", "\"${clientVersionName}\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                // `*IntegrationTest` classes need a live server; opt in with
                // -PsidetrackIntegration instead of silently skipping them in the default run.
                if (!runIntegrationTests) it.filter.excludeTestsMatching("*IntegrationTest")
            }
        }
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.sidekit.sdk)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)
}

// Coverage ratchet for this module's own tests. The floor sits two points under what the module
// measures today (66.61% line / 38.22% branch) and only ever moves up.
kover {
    reports {
        variant("debug") {
            verify {
                rule("line coverage") {
                    minBound(64)
                }
                rule("branch coverage") {
                    minBound(36, CoverageUnit.BRANCH, AggregationType.COVERED_PERCENTAGE)
                }
            }
        }
    }
}
