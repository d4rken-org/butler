import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension

plugins {
    id("com.android.library")
    id("kotlin-parcelize")
    id("com.google.devtools.ksp")
    id("projectConfig")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

apply(plugin = "dagger.hilt.android.plugin")
apply(plugin = "org.jetbrains.kotlinx.kover")

android {
    namespace = "${projectConfig.packageName}.common"

    setupLibraryDefaults(projectConfig)

    setupModuleBuildTypes()

    setupCompileOptions()

    setupKotlinOptions()

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
        //noinspection WrongGradleMethod
        tasks.withType<Test> {
            useJUnitPlatform()
            setupTestLogging()
            setupTestJvm()
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar)
    testImplementation(project(":app-common-test"))

    addAndroidCore()
    addAndroidUI()
    addNavigation3()
    addDI()
    addCoroutines()
    addSerialization()
    addIO()
    addTesting()

    // Compose UI testing with Robolectric
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    addCoil()
    addLottie()
    addRoomDb()
}

// Kover's verify rules can't filter per-rule (only the report can), so to gate ONLY the
// well-covered SharedResource concurrency code we scope THIS module's own Kover report to that
// package and enforce a floor on it. The broad cross-module aggregate lives in the root build and
// is unaffected (it re-aggregates raw coverage with its own filters).
configure<KoverProjectExtension> {
    reports {
        filters { includes { classes("eu.darken.butler.common.sharedresource.*") } }
        verify {
            rule("SharedResource line coverage") {
                // Currently ~87%; floor leaves headroom but still guards against the concurrency
                // code rotting. Run the gate with :app-common:koverVerifyFossDebug.
                minBound(80)
            }
        }
    }
}