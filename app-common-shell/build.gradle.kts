plugins {
    id("com.android.library")
    id("kotlin-parcelize")
    id("projectConfig")
    id("com.google.devtools.ksp")
}

apply(plugin = "dagger.hilt.android.plugin")
apply(plugin = "org.jetbrains.kotlinx.kover")

android {
    namespace = "${projectConfig.packageName}.common.shell"

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

    addTesting()
    testImplementation(project(":app-common-test"))
}