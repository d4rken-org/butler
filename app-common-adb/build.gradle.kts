plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("kotlin-android")
    id("kotlin-parcelize")
    id("com.google.devtools.ksp")
    id("projectConfig")
    id("org.jetbrains.kotlin.plugin.serialization")
}

apply(plugin = "dagger.hilt.android.plugin")

android {
    namespace = "${projectConfig.packageName}.common.adb"

    setupLibraryDefaults(projectConfig)

    setupModuleBuildTypes()

    buildFeatures {
        aidl = true
    }

    setupCompileOptions()

    setupKotlinOptions()


}

dependencies {
    coreLibraryDesugaring(libs.desugar)
    implementation(project(":app-common"))

    addAndroidCore()
    addDI()
    addCoroutines()
    addSerialization()
    addIO()

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}