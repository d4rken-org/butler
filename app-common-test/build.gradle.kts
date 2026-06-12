plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("kotlin-android")
    id("com.google.devtools.ksp")
    id("projectConfig")
}

android {
    namespace = "${projectConfig.packageName}.common.test"

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
        }
    }

    packaging {
        resources {
            excludes.add("META-INF/*")
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar)
    implementation(project(":app-common"))
    implementation(project(":app-common-io"))

    addAndroidCore()
    addIO()
    addSerialization()

    // Compose testing with Robolectric
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.compose.ui.test.junit4)
    implementation(libs.robolectric)

    implementation(libs.junit4)
    implementation(libs.junit.vintage.engine.ct)
    implementation(libs.androidx.test.core.ktx.ct)
    implementation(libs.room.testing)

    implementation(libs.mockk.ct)

    runtimeOnly(libs.jupiter.engine.ct)
    implementation(libs.jupiter.api.ct)
    implementation(libs.jupiter.params.ct)

    implementation(libs.kotest.runner.ct)
    implementation(libs.kotest.assertions.ct)
    implementation(libs.kotest.property.ct)
}