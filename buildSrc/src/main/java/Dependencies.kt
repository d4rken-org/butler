import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.exclude
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

private val Project.libs: VersionCatalog
    get() = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

private fun Project.add(configuration: String, alias: String) {
    dependencies.addProvider(
        configuration,
        libs.findLibrary(alias).orElseThrow { IllegalArgumentException("Missing catalog alias: $alias") },
    )
}

private fun Project.add(configuration: String, alias: String, action: Action<ExternalModuleDependency>) {
    dependencies.addProvider(
        configuration,
        libs.findLibrary(alias).orElseThrow { IllegalArgumentException("Missing catalog alias: $alias") },
        action,
    )
}

private fun Project.addPlatform(configuration: String, alias: String) {
    dependencies.add(
        configuration,
        dependencies.platform(
            libs.findLibrary(alias).orElseThrow { IllegalArgumentException("Missing catalog alias: $alias") }.get(),
        ),
    )
}

fun Project.addDI() {
    add("implementation", "dagger")
    add("implementation", "dagger-android")

    add("ksp", "dagger-compiler")
    add("kspTest", "dagger-compiler")

    add("ksp", "dagger-android-processor")
    add("kspTest", "dagger-android-processor")

    add("implementation", "hilt-android")
    add("ksp", "hilt-android-compiler")
    add("kspTest", "hilt-android-compiler")

    add("testImplementation", "hilt-android-testing")

    add("androidTestImplementation", "hilt-android-testing")
    add("kspAndroidTest", "hilt-android-compiler")
}

fun Project.addCoroutines() {
    add("implementation", "kotlin-stdlib")
    add("implementation", "coroutines-core")
    add("implementation", "coroutines-android")
    add("implementation", "kotlin-reflect")

    add("testImplementation", "kotlin-reflect")
    // 2 files found with path 'win32-x86-64/attach_hotspot_windows.dll'
    add("testImplementation", "coroutines-test") {
        exclude("org.jetbrains.kotlinx", "kotlinx-coroutines-debug")
    }
    add("androidTestImplementation", "coroutines-test") {
        exclude("org.jetbrains.kotlinx", "kotlinx-coroutines-debug")
    }
}

fun Project.addCoil() {
    add("implementation", "coil")
    add("implementation", "coil-compose")
    add("implementation", "coil-video")
    add("implementation", "coil-network-okhttp")
}

fun Project.addZoomableImage() {
    add("implementation", "telephoto-zoomable-image")
    add("implementation", "telephoto-sub-sampling-image")
}

fun Project.addLottie() {
    add("implementation", "lottie")
    add("implementation", "lottie-compose")
}

fun Project.addSerialization() {
    add("implementation", "serialization-core")
    add("implementation", "serialization-json")
}

fun Project.addIO() {
    add("implementation", "okio")
}

fun Project.addNetworkFs() {
    add("implementation", "smbj")

    add("testImplementation", "testcontainers")
    add("testImplementation", "testcontainers-junit-jupiter")
}

fun Project.addArchive() {
    add("implementation", "commons-compress")
    add("implementation", "zip4j")
}

fun Project.addRetrofit() {
    add("implementation", "retrofit")
    add("implementation", "retrofit-serialization-converter")
    add("implementation", "retrofit-converter-scalars")
    add("implementation", "okhttp-logging")

    add("testImplementation", "okhttp-mockwebserver")
}

fun Project.addAndroidCore() {
    add("implementation", "androidx-core-ktx")
    add("implementation", "androidx-appcompat")
    add("implementation", "androidx-annotation")

    add("implementation", "androidx-preference-ktx")
    add("implementation", "androidx-datastore-preferences")
}

fun Project.addRoomDb() {
    add("implementation", "room-runtime")
    add("implementation", "room-ktx")
    add("ksp", "room-compiler")
    add("testImplementation", "room-testing")
}

fun Project.addWorkerManager() {
    add("implementation", "work-runtime")
    add("testImplementation", "work-testing")
    add("implementation", "work-runtime-ktx")

    add("implementation", "hilt-work")
    add("ksp", "hilt-compiler-androidx")
}

fun Project.addAndroidUI() {
    add("implementation", "androidx-activity-ktx")
    add("implementation", "androidx-fragment-ktx")

    add("implementation", "lifecycle-viewmodel-ktx")
    add("implementation", "lifecycle-viewmodel-savedstate")
    add("implementation", "lifecycle-common-java8")
    add("implementation", "lifecycle-process")
    add("implementation", "lifecycle-livedata-ktx")

    add("implementation", "constraintlayout")
    add("implementation", "material")

    addPlatform("implementation", "androidx-compose-bom")
    addPlatform("androidTestImplementation", "androidx-compose-bom")

    add("implementation", "compose-foundation")
    add("implementation", "compose-material3")
    add("implementation", "compose-ui-preview")
    add("debugImplementation", "compose-ui-tooling")
    add("androidTestImplementation", "compose-ui-test-junit4")
    add("debugImplementation", "compose-ui-test-manifest")

    add("implementation", "compose-material-icons-extended")
    add("implementation", "compose-adaptive")

    add("implementation", "androidx-activity-compose")
    add("implementation", "lifecycle-viewmodel-compose")

    add("implementation", "hilt-lifecycle-viewmodel-compose")

    add("implementation", "accompanist-drawablepainter")

    // Tied to the compose-material3 dependency above: the marker only resolves where material3 is
    // on the compile classpath, so applying it project-wide warns in the non-UI modules.
    tasks.withType(KotlinCompile::class.java) {
        compilerOptions.freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

fun Project.addNavigation3() {
    add("implementation", "navigation3-runtime")
    add("implementation", "navigation3-ui")
    add("implementation", "navigation3-ui-android")

    add("implementation", "lifecycle-viewmodel-navigation3")
    add("implementation", "lifecycle-viewmodel-navigation3-android")

    add("implementation", "adaptive-navigation3")
    add("implementation", "adaptive-navigation3-android")

    add("implementation", "serialization-core")
}

fun Project.addTesting() {
    add("testImplementation", "junit4")
    add("testImplementation", "junit-vintage-engine")
    add("testImplementation", "androidx-test-core-ktx")

    add("testImplementation", "mockk")
    add("androidTestImplementation", "mockk-android")

    add("testRuntimeOnly", "jupiter-engine")
    add("testImplementation", "jupiter-api")
    add("testImplementation", "jupiter-params")

    add("testImplementation", "kotest-runner")
    add("testImplementation", "kotest-assertions")
    add("testImplementation", "kotest-property")
    add("androidTestImplementation", "kotest-assertions")
    add("androidTestImplementation", "kotest-property")

    add("testImplementation", "arch-core-testing")
    add("androidTestImplementation", "arch-core-testing")
    add("debugImplementation", "androidx-test-core-ktx")

    add("androidTestImplementation", "androidx-test-junit-android")
    add("androidTestImplementation", "espresso-core")

    add("androidTestImplementation", "androidx-test-runner")
    add("androidTestImplementation", "androidx-test-rules")
    add("androidTestImplementation", "espresso-core")
    add("androidTestImplementation", "espresso-contrib")
    add("androidTestImplementation", "espresso-intents")
    add("androidTestImplementation", "espresso-idling-concurrent")

    add("testImplementation", "robolectric")
    add("testImplementation", "androidx-test-junit-unit")
}
