plugins {
    id("com.android.library")
    id("kotlin-parcelize")
    id("com.google.devtools.ksp")
    id("projectConfig")
    id("org.jetbrains.kotlin.plugin.serialization")
}

apply(plugin = "dagger.hilt.android.plugin")
apply(plugin = "org.jetbrains.kotlinx.kover")

setupRoomSchemas()

android {
    namespace = "${projectConfig.packageName}.common.io"

    setupLibraryDefaults(projectConfig)

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
            setupTestJvm()
        }
    }

    packaging {
        resources {
            // Identical in bcprov, bcpkix and bcutil (via :lib-ssh); collides in the androidTest APK.
            pickFirsts.add("META-INF/LICENSE.md")
            // Build metadata of each sshd-* jar (via :lib-ssh).
            pickFirsts.add("META-INF/DEPENDENCIES")
        }
    }

    sourceSets {
        getByName("test") {
            assets.directories.add("$projectDir/schemas")
            // SftpGatewayIntegrationTest uses lib-ssh's test-only host keys.
            resources.directories.add("${rootProject.projectDir}/lib-ssh/src/test/resources")
        }
        getByName("androidTest") {
            assets.directories.add("${rootProject.projectDir}/lib-ssh/src/test/resources")
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar)
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
    addArchive()
    implementation(project(":lib-smb"))
    implementation(project(":lib-ssh"))
    addRoomDb()
    addWorkerManager()

    addTesting()
    testImplementation(libs.testcontainers)
    // An in-process SFTP server whose users do not start in `/`.
    testImplementation(libs.sshd.core)
    testImplementation(libs.sshd.sftp)
    testImplementation(project(":app-common-test"))
}
