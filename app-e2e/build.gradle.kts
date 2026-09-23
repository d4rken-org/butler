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
    }

    targetProjectPath = ":app"
    // The tests run in their own process, so they can clear, stop and relaunch the app under test.
    experimentalProperties["android.experimental.self-instrumenting"] = true

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
