plugins {
    id("com.android.application")
    id("kotlin-android")
    id("kotlin-parcelize")
    id("projectConfig")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("kotlin-kapt")
}
apply(plugin = "dagger.hilt.android.plugin")

val commitHashProvider = providers.of(CommitHashValueSource::class) {}

android {
    if (projectConfig.compileSdkPreview != null) {
        compileSdkPreview = projectConfig.compileSdkPreview
    } else {
        compileSdk = projectConfig.compileSdk
    }

    defaultConfig {
        namespace = projectConfig.packageName

        minSdk = projectConfig.minSdk
        if (projectConfig.targetSdkPreview != null) {
            targetSdkPreview = projectConfig.targetSdkPreview
        } else {
            targetSdk = projectConfig.targetSdk
        }

        versionCode = projectConfig.version.code.toInt()
        versionName = projectConfig.version.name

        testInstrumentationRunner = "eu.darken.butler.HiltTestRunner"

        buildConfigField("String", "PACKAGENAME", "\"${projectConfig.packageName}\"")
        buildConfigField("String", "GITSHA", "\"${commitHashProvider.get()}\"")
        buildConfigField("String", "VERSION_CODE", "\"${projectConfig.version.code}\"")
        buildConfigField("String", "VERSION_NAME", "\"${projectConfig.version.name}\"")

        javaCompileOptions {
            annotationProcessorOptions {
                arguments(
                    mapOf("room.schemaLocation" to "$projectDir/schemas")
                )
            }
        }
    }

    signingConfigs {
        val basePath = File(System.getProperty("user.home"), ".appconfig/${projectConfig.packageName}")
        create("releaseFoss") {
            setupCredentials(File(basePath, "signing-foss.properties"))
        }
        create("releaseGplay") {
            setupCredentials(File(basePath, "signing-gplay-upload.properties"))
        }
    }

    flavorDimensions.add("version")
    productFlavors {
        create("foss") {
            dimension = "version"
            signingConfig = signingConfigs["releaseFoss"]
            // The info block is encrypted and can only be read by google
            dependenciesInfo {
                includeInApk = false
                includeInBundle = false
            }
        }
        create("gplay") {
            dimension = "version"
            signingConfig = signingConfigs["releaseGplay"]
        }
    }

    buildTypes {
        val customProguardRules = fileTree(File(projectDir, "proguard")) {
            include("*.pro")
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            proguardFiles(*customProguardRules.toList().toTypedArray())
            proguardFiles("proguard-rules-debug.pro")
        }
        create("beta") {
            lint {
                abortOnError = true
                fatal.add("StopShip")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            proguardFiles(*customProguardRules.toList().toTypedArray())
        }
        release {
            lint {
                abortOnError = true
                fatal.add("StopShip")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            proguardFiles(*customProguardRules.toList().toTypedArray())
        }
    }

    buildOutputs.all {
        val variantOutputImpl = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
        val variantName: String = variantOutputImpl.name

        if (listOf("release", "beta").any { variantName.lowercase().contains(it) }) {
            val outputFileName = projectConfig.packageName +
                "-v${defaultConfig.versionName}-${defaultConfig.versionCode}" +
                "-${variantName.uppercase()}.apk"

            variantOutputImpl.outputFileName = outputFileName
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

    sourceSets {
        getByName("test") {
            resources.srcDirs("src/main/assets")
        }
        getByName("androidTest") {
            assets.srcDirs(files("$projectDir/schemas"))
        }
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }

    packaging {
        resources {
            excludes.add("attach_hotspot_windows.dll")
        }
    }
}

setupKotlinOptions()

afterEvaluate {
    tasks {
        named("bundleGplayBeta") {
            dependsOn("lintVitalGplayBeta")
        }
        named("bundleGplayRelease") {
            dependsOn("lintVitalGplayRelease")
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:${Versions.Desugar.core}")

    implementation(project(":app-common"))
    testImplementation(project(":app-common-test"))
    implementation(project(":app-common-root"))
    implementation(project(":app-common-adb"))
    implementation(project(":app-common-io"))
    implementation(project(":app-common-pkgs"))
    implementation(project(":app-common-shell"))
    implementation(project(":app-workspace"))
    implementation(project(":app-workspace-explorer"))
    implementation(project(":app-workspace-searcher"))
    implementation(project(":app-workspace-editor"))
    implementation(project(":app-workspace-templates"))
    implementation(project(":app-workspace-apps"))
    implementation(project(":app-workspace-saver"))
    implementation(project(":app-workspace-developer"))
    implementation(project(":app-provider-documents"))

    addDI()
    addCoroutines()
    addSerialization()
    addIO()
    addRetrofit()

    "gplayImplementation"("com.android.billingclient:billing:7.1.1")
    "gplayImplementation"("com.android.billingclient:billing-ktx:7.1.1")

    "gplayImplementation"("com.google.android.play:review:2.0.2")
    "gplayImplementation"("com.google.android.play:review-ktx:2.0.2")

    addAndroidCore()
    addAndroidUI()
    addWorkerManager()

    addNavigation3()

    addRoomDb()

    addTesting()

    // Compose UI testing with Robolectric
    testImplementation(platform("androidx.compose:compose-bom:2025.06.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")

    implementation("io.github.z4kn4fein:semver:3.0.0")

    // Drag and drop support for LazyColumn
    implementation("sh.calvin.reorderable:reorderable:2.5.1")

    addCoil()
}