plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("kotlin-android")
    id("kotlin-parcelize")
    id("com.google.devtools.ksp")
    id("projectConfig")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("kotlin-kapt")
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
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:${Versions.Desugar.core}")
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
    testImplementation(platform("androidx.compose:compose-bom:2025.06.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")

    addNavigation3()
    addCoil()
    addLottie()
    addRoomDb()

    "gplayImplementation"("com.android.billingclient:billing:7.1.1")
    "gplayImplementation"("com.android.billingclient:billing-ktx:7.1.1")
}