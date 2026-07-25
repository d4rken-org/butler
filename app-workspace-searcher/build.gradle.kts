plugins {
    id("com.android.library")
    id("kotlin-parcelize")
    id("com.google.devtools.ksp")
    id("projectConfig")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

apply(plugin = "dagger.hilt.android.plugin")

setupRoomSchemas()

android {
    namespace = "${projectConfig.packageName}.searcher"

    setupLibraryDefaults(projectConfig)

    setupModuleBuildTypes()

    setupCompileOptions()

    setupKotlinOptions()

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
        //noinspection WrongGradleMethod
        tasks.withType<Test> {
            useJUnitPlatform()
            setupTestLogging()
        }
    }

    sourceSets {
        getByName("test") {
            assets.directories.add("$projectDir/schemas")
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

    addCoil()
    addRoomDb()

    // Compose UI testing with Robolectric
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}