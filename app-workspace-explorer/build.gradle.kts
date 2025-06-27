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
    namespace = "${projectConfig.packageName}.explorer"

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
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:${Versions.Desugar.core}")
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

    addNavigation3()
    addCoil()
}