plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("kotlin-android")
    id("kotlin-parcelize")
    id("com.google.devtools.ksp")
    id("projectConfig")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

apply(plugin = "dagger.hilt.android.plugin")

android {
    namespace = "${projectConfig.packageName}.common"

    setupLibraryDefaults(projectConfig)

    flavorDimensions.add("version")
    productFlavors {
        create("foss") {
            dimension = "version"
            isDefault = true
        }
        create("gplay") {
            dimension = "version"
        }
    }

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

    "gplayImplementation"(libs.billing.core)
    "gplayImplementation"(libs.billing.ktx)
}