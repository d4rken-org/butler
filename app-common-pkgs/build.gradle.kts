plugins {
    id("com.android.library")
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