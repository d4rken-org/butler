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
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:${Versions.Desugar.core}")
    implementation(project(":app-common"))
    implementation(project(":app-common-io"))

    addAndroidCore()
    addIO()
    addSerialization()

    // Compose testing with Robolectric
    val composeBom = platform("androidx.compose:compose-bom:2025.12.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui-test-junit4")
    implementation("org.robolectric:robolectric:4.14.1")

    implementation("junit:junit:4.13.2")
    implementation("org.junit.vintage:junit-vintage-engine:5.13.0")
    implementation("androidx.test:core-ktx:1.6.1")

    implementation("io.mockk:mockk:1.14.2")

    runtimeOnly("org.junit.jupiter:junit-jupiter-engine:5.13.0")
    implementation("org.junit.jupiter:junit-jupiter-api:5.13.0")
    implementation("org.junit.jupiter:junit-jupiter-params:5.13.0")


    implementation("io.kotest:kotest-runner-junit5:5.9.1")
    implementation("io.kotest:kotest-assertions-core-jvm:5.9.1")
    implementation("io.kotest:kotest-property-jvm:5.9.1")
}