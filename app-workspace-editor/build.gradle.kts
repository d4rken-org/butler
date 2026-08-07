plugins {
    id("com.android.library")
    id("kotlin-parcelize")
    id("com.google.devtools.ksp")
    id("projectConfig")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

apply(plugin = "dagger.hilt.android.plugin")

android {
    namespace = "${projectConfig.packageName}.editor"

    setupLibraryDefaults(projectConfig)

    setupModuleBuildTypes()

    setupCompileOptions()

    setupKotlinOptions()

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
        //noinspection WrongGradleMethod
        tasks.withType<Test> {
            useJUnitPlatform {
                // Manual benchmarks stay out of normal runs. An excluded tag removes the
                // descriptor from the test plan entirely, unlike @Disabled which reports as
                // skipped and would permanently occupy CI's worker-death heuristic.
                // Opt in with -PrunEditorBenchmarks.
                if (!project.hasProperty("runEditorBenchmarks")) excludeTags("manual-benchmark")
            }
            setupTestLogging()
            setupTestJvm()
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar)
    testImplementation(project(":app-common-test"))

    implementation(project(":app-common"))
    implementation(project(":app-common-io"))
    implementation(project(":app-workspace"))

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
}