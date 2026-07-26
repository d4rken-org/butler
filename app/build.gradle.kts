plugins {
    id("com.android.application")
    id("kotlin-parcelize")
    id("projectConfig")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    alias(libs.plugins.screenshot)
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
    }

    signingConfigs {
        val basePath = File(System.getProperty("user.home"), ".config/projects/${projectConfig.packageName}")
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

    buildFeatures {
        buildConfig = true
        compose = true
    }

    experimentalProperties["android.experimental.enableScreenshotTest"] = true

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
            setupTestJvm()
        }
    }

    sourceSets {
        getByName("test") {
            resources.directories.add("src/main/assets")
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

// Rename release/beta APKs to include version and variant. The legacy variant output
// API is removed by AGP's new DSL, so this uses the new androidComponents variant API.
// The suffix reproduces AGP's dash-separated base name (e.g. FOSS-BETA) rather than
// variant.name's camelCase (fossBeta), preserving the pre-migration file names.
androidComponents {
    onVariants { variant ->
        if (variant.buildType != "release" && variant.buildType != "beta") return@onVariants
        val baseName = (variant.productFlavors.map { it.second } + listOfNotNull(variant.buildType))
            .joinToString("-")
        val output = variant.outputs.single()
        output.outputFileName.set(
            "${projectConfig.packageName}" +
                "-v${projectConfig.version.name}-${projectConfig.version.code}" +
                "-${baseName.uppercase()}.apk",
        )
    }
}

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
    coreLibraryDesugaring(libs.desugar)

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
    implementation(project(":app-workspace-history"))
    implementation(project(":app-workspace-bugreport"))
    implementation(project(":app-provider-documents"))

    addDI()
    addCoroutines()
    addSerialization()
    addIO()
    addRetrofit()

    "gplayImplementation"(libs.billing.core)
    "gplayImplementation"(libs.billing.ktx)

    "gplayImplementation"(libs.play.review.core)
    "gplayImplementation"(libs.play.review.ktx)

    addAndroidCore()
    addAndroidUI()
    addWorkerManager()

    addNavigation3()

    addRoomDb()

    addTesting()

    // Compose UI testing with Robolectric
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    implementation(libs.semver)

    // Drag and drop support for LazyColumn
    implementation(libs.reorderable)

    addCoil()

    // Compose Preview Screenshot Testing
    "screenshotTestImplementation"(platform(libs.androidx.compose.bom))
    "screenshotTestImplementation"(libs.screenshot.validation.api)
    "screenshotTestImplementation"(libs.compose.ui.tooling)
}