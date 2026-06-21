import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension

plugins {
    id("projectConfig")
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kover) apply false
}

allprojects {
    tasks.withType<Test> {
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showExceptions = true
            showCauses = true
            showStackTraces = true
        }
    }
}

// --- Code coverage (Kover) -------------------------------------------------------------------
// Aggregates unit-test coverage for the core library modules into one merged report. Run:
//   ./gradlew :koverHtmlReport       (merged HTML, at build/reports/kover/html/index.html)
//   ./gradlew :koverXmlReport        (merged XML, for CI)
//   ./gradlew :koverPrintCoverage    (prints the merged % to the console)
// Per-module reports use the variant-suffixed tasks instead, e.g.
//   ./gradlew :app-common:koverHtmlReportFossDebug
// The package-scoped coverage gate lives in app-common/build.gradle.kts (koverVerify…).
apply(plugin = "org.jetbrains.kotlinx.kover")

dependencies {
    "kover"(project(":app-common"))
    "kover"(project(":app-common-shell"))
    "kover"(project(":app-common-io"))
    "kover"(project(":app-common-root"))
    "kover"(project(":app-common-adb"))
}

configure<KoverProjectExtension> {
    reports {
        filters {
            excludes {
                // Generated / boilerplate — not meaningful to measure.
                annotatedBy(
                    "dagger.Module",
                    "dagger.internal.DaggerGenerated",
                    "javax.annotation.processing.Generated",
                )
                classes(
                    "*_Factory", "*_Factory\$*", "*_MembersInjector",
                    "Hilt_*", "*.Hilt_*", "*.Dagger*", "hilt_aggregated_deps.*", "*_HiltModules*",
                    "*.BuildConfig", "*.R", "*.R\$*", "*.databinding.*",
                    // AIDL-generated binder code (Stub / Stub$Proxy / Default) across all modules.
                    "*\$Stub", "*\$Stub\$*", "*\$Default",
                )
            }
        }
    }
}

tasks.register("clean").configure {
    delete("build")
}