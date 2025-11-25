plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("kotlin-android")
    id("kotlin-parcelize")
    id("com.google.devtools.ksp")
    id("projectConfig")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("kotlin-kapt")
}

apply(plugin = "dagger.hilt.android.plugin")

android {
    namespace = "${projectConfig.packageName}.common.io"

    setupLibraryDefaults(projectConfig)

    defaultConfig {
        javaCompileOptions {
            annotationProcessorOptions {
                arguments(
                    mapOf("room.schemaLocation" to "$projectDir/schemas")
                )
            }
        }
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    setupModuleBuildTypes()

    buildFeatures {
        buildConfig = true
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
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:${Versions.Desugar.core}")
    implementation(project(":app-common"))
    implementation(project(":app-common-root"))
    implementation(project(":app-common-adb"))
    implementation(project(":app-common-shell"))

    addAndroidCore()
    addAndroidUI()
    addDI()
    addCoroutines()
    addSerialization()
    addIO()
    addRoomDb()
    addWorkerManager()

    addTesting()
    testImplementation(project(":app-common-test"))
}