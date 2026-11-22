import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kover)
}

android {
    // Unique per module; :app owns io.github.manuelrtsch.sidetrack. Sources keep their
    // io.github.manuelrtsch.sidetrack.* packages; this namespace only drives generated code.
    namespace = "io.github.manuelrtsch.sidetrack.local"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }
    defaultConfig {
        minSdk = 31
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.kotlinx.coroutines.test)
}

// Coverage ratchet for this module's own tests. The catalog, the playlist file format and the
// favourites store are tested against fakes; what stays uncovered is the MediaStore/ContentResolver
// edge, which no JVM test can exercise. Measured 61.6% line / 43.8% branch when the provider
// landed, so the floors start there and only move up.
kover {
    reports {
        variant("debug") {
            verify {
                rule("line coverage") {
                    minBound(61)
                }
                rule("branch coverage") {
                    minBound(43, CoverageUnit.BRANCH, AggregationType.COVERED_PERCENTAGE)
                }
            }
        }
    }
}
