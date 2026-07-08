plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("kotlin-android")
    id("kotlin-parcelize")
    id("projectConfig")
    id("com.google.devtools.ksp")
}

apply(plugin = "dagger.hilt.android.plugin")

android {
    namespace = "${projectConfig.packageName}.common.pkgs"

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

    // JNA (via root/shell) ships dual-license files in two jars; needed so the androidTest apk merges.
    packaging {
        resources {
            excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar)
    implementation(project(":app-common"))
    implementation(project(":app-common-shell"))
    implementation(project(":app-common-io"))
    implementation(project(":app-common-root"))

    addAndroidCore()
    addDI()
    addCoroutines()
    addIO()

    addTesting()
    testImplementation(project(":app-common-test"))
}