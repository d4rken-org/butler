plugins {
    id("com.android.application")
}

android {
    namespace = "eu.darken.butler.saftestprovider"
    compileSdk = 37

    defaultConfig {
        applicationId = "eu.darken.butler.saftestprovider"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions.unitTests.isIncludeAndroidResources = true
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) {
        it.enable = false
    }
}

dependencies {
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
}
