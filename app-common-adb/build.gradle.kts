plugins {
    id("com.android.library")
    id("kotlin-parcelize")
    id("com.google.devtools.ksp")
    id("projectConfig")
    id("org.jetbrains.kotlin.plugin.serialization")
}

apply(plugin = "dagger.hilt.android.plugin")
apply(plugin = "org.jetbrains.kotlinx.kover")

android {
    namespace = "${projectConfig.packageName}.common.adb"

    setupLibraryDefaults(projectConfig)

    setupModuleBuildTypes()

    buildFeatures {
        aidl = true
    }

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

    addTesting()
    testImplementation(project(":app-common-test"))
}