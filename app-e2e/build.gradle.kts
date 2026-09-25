plugins {
    id("com.android.test")
    id("projectConfig")
}

android {
    namespace = "${projectConfig.packageName}.e2e"
    compileSdk = projectConfig.compileSdk

    defaultConfig {
        minSdk = projectConfig.minSdk
        targetSdk = projectConfig.targetSdk
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Its phases need an APK swap in between, so they run outside Gradle.
        testInstrumentationRunnerArguments["notClass"] = "eu.darken.butler.e2e.UpgradeTest"
    }

    targetProjectPath = ":app"
    // The tests run in their own process, so they can clear, stop and relaunch the app under test.
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        create("beta") {
            isDebuggable = true
            signingConfig = signingConfigs["debug"]
        }
    }

    flavorDimensions.add("version")
    productFlavors {
        create("foss") { dimension = "version" }
        create("gplay") { dimension = "version" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

setupKotlinOptions()

dependencies {
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.junit.android)
    implementation(libs.androidx.test.uiautomator)
}
