import org.gradle.api.Action
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.kotlin.dsl.DependencyHandlerScope
import org.gradle.kotlin.dsl.accessors.runtime.addDependencyTo
import org.gradle.kotlin.dsl.exclude

private fun DependencyHandler.implementation(dependencyNotation: Any): Dependency? =
    add("implementation", dependencyNotation)

private fun DependencyHandler.testImplementation(dependencyNotation: Any): Dependency? =
    add("testImplementation", dependencyNotation)

private fun DependencyHandler.testImplementation(
    dependencyNotation: String,
    dependencyConfiguration: Action<ExternalModuleDependency>
): ExternalModuleDependency = addDependencyTo(
    this, "androidTestImplementation", dependencyNotation, dependencyConfiguration
)

private fun DependencyHandler.kapt(dependencyNotation: Any): Dependency? =
    add("kapt", dependencyNotation)

private fun DependencyHandler.ksp(dependencyNotation: Any): Dependency? =
    add("ksp", dependencyNotation)

private fun DependencyHandler.kaptTest(dependencyNotation: Any): Dependency? =
    add("kaptTest", dependencyNotation)

private fun DependencyHandler.androidTestImplementation(dependencyNotation: Any): Dependency? =
    add("androidTestImplementation", dependencyNotation)

private fun DependencyHandler.androidTestImplementation(
    dependencyNotation: String,
    dependencyConfiguration: Action<ExternalModuleDependency>
): ExternalModuleDependency = addDependencyTo(
    this, "androidTestImplementation", dependencyNotation, dependencyConfiguration
)

private fun DependencyHandler.kaptAndroidTest(dependencyNotation: Any): Dependency? =
    add("kaptAndroidTest", dependencyNotation)

private fun DependencyHandler.testRuntimeOnly(dependencyNotation: Any): Dependency? =
    add("testRuntimeOnly", dependencyNotation)

private fun DependencyHandler.debugImplementation(dependencyNotation: Any): Dependency? =
    add("debugImplementation", dependencyNotation)

fun DependencyHandlerScope.addDI() {
    implementation("com.google.dagger:dagger:${Versions.Dagger.core}")
    implementation("com.google.dagger:dagger-android:${Versions.Dagger.core}")

    kapt("com.google.dagger:dagger-compiler:${Versions.Dagger.core}")
    kaptTest("com.google.dagger:dagger-compiler:${Versions.Dagger.core}")

    kapt("com.google.dagger:dagger-android-processor:${Versions.Dagger.core}")
    kaptTest("com.google.dagger:dagger-android-processor:${Versions.Dagger.core}")

    implementation("com.google.dagger:hilt-android:${Versions.Dagger.core}")
    kapt("com.google.dagger:hilt-android-compiler:${Versions.Dagger.core}")
    kaptTest("com.google.dagger:hilt-android-compiler:${Versions.Dagger.core}")

    testImplementation("com.google.dagger:hilt-android-testing:${Versions.Dagger.core}")

    androidTestImplementation("com.google.dagger:hilt-android-testing:${Versions.Dagger.core}")
    kaptAndroidTest("com.google.dagger:hilt-android-compiler:${Versions.Dagger.core}")
}

fun DependencyHandlerScope.addCoroutines() {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:${Versions.Kotlin.core}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${Versions.Kotlin.coroutines}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${Versions.Kotlin.coroutines}")
    implementation("org.jetbrains.kotlin:kotlin-reflect:${Versions.Kotlin.core}")

    testImplementation("org.jetbrains.kotlin:kotlin-reflect:${Versions.Kotlin.core}")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${Versions.Kotlin.coroutines}") {
        // 2 files found with path 'win32-x86-64/attach_hotspot_windows.dll'
        exclude("org.jetbrains.kotlinx", "kotlinx-coroutines-debug")
    }
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${Versions.Kotlin.coroutines}") {
        // 2 files found with path 'win32-x86-64/attach_hotspot_windows.dll'
        exclude("org.jetbrains.kotlinx", "kotlinx-coroutines-debug")
    }
}

fun DependencyHandlerScope.addCoil() {
    val version = "3.2.0"
    implementation("io.coil-kt.coil3:coil:$version")
    implementation("io.coil-kt.coil3:coil-video:$version")
    implementation("io.coil-kt.coil3:coil-network-okhttp:$version")
}

fun DependencyHandlerScope.addLottie() {
    implementation("com.airbnb.android:lottie:6.6.6")
    implementation("com.airbnb.android:lottie-compose:6.6.6")
}

fun DependencyHandlerScope.addSerialization() {
    val version = "1.8.1"
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:$version")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$version")
}

fun DependencyHandlerScope.addIO() {
    implementation("com.squareup.okio:okio:3.13.0")
}

fun DependencyHandlerScope.addRetrofit() {
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("com.squareup.retrofit2:converter-scalars:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

fun DependencyHandlerScope.addAndroidCore() {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.annotation:annotation:1.4.0")

    implementation("androidx.preference:preference-ktx:1.2.0")
    implementation("androidx.datastore:datastore-preferences:1.0.0")
}

fun DependencyHandlerScope.addRoomDb() {
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")
}

fun DependencyHandlerScope.addWorkerManager() {
    val version = "2.10.1"
    implementation("androidx.work:work-runtime:$version")
    testImplementation("androidx.work:work-testing:$version")
    implementation("androidx.work:work-runtime-ktx:$version")

    implementation("androidx.hilt:hilt-work:1.0.0")
    kapt("androidx.hilt:hilt-compiler:1.0.0")
}

fun DependencyHandlerScope.addAndroidUI() {
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    implementation("androidx.lifecycle:lifecycle-extensions:2.2.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.5.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.5.1")
    implementation("androidx.lifecycle:lifecycle-common-java8:2.5.1")
    implementation("androidx.lifecycle:lifecycle-process:2.5.1")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.5.1")

    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("com.google.android.material:material:1.13.0-alpha12")

    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")


    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3.adaptive:adaptive")

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")

    implementation("androidx.hilt:hilt-navigation-compose:1.3.0-alpha01")

    implementation("com.google.accompanist:accompanist-drawablepainter:0.37.3")
}

fun DependencyHandlerScope.addNavigation3() {
    implementation("androidx.navigation3:navigation3-runtime:1.0.0-alpha08")
    implementation("androidx.navigation3:navigation3-ui:1.0.0-alpha08")
    implementation("androidx.navigation3:navigation3-ui-android:1.0.0-alpha08")

    implementation("androidx.lifecycle:lifecycle-viewmodel-navigation3:2.10.0-alpha03")
    implementation("androidx.lifecycle:lifecycle-viewmodel-navigation3-android:2.10.0-alpha03")

    implementation("androidx.compose.material3.adaptive:adaptive-navigation3:1.0.0-alpha02")
    implementation("androidx.compose.material3.adaptive:adaptive-navigation3-android:1.0.0-alpha02")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.1")
}

fun DependencyHandlerScope.addTesting() {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.junit.vintage:junit-vintage-engine:5.8.2")
    testImplementation("androidx.test:core-ktx:1.4.0")

    testImplementation("io.mockk:mockk:1.12.4")
    androidTestImplementation("io.mockk:mockk-android:1.12.4")

    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.2")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.8.2")


    testImplementation("io.kotest:kotest-runner-junit5:5.3.0")
    testImplementation("io.kotest:kotest-assertions-core-jvm:5.3.0")
    testImplementation("io.kotest:kotest-property-jvm:5.3.0")
    androidTestImplementation("io.kotest:kotest-assertions-core-jvm:5.3.0")
    androidTestImplementation("io.kotest:kotest-property-jvm:5.3.0")

    testImplementation("android.arch.core:core-testing:1.1.1")
    androidTestImplementation("android.arch.core:core-testing:1.1.1")
    debugImplementation("androidx.test:core-ktx:1.4.0")

    androidTestImplementation("androidx.test.ext:junit:1.1.3")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.4.0")

    androidTestImplementation("androidx.test:runner:1.4.0")
    androidTestImplementation("androidx.test:rules:1.4.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.4.0")
    androidTestImplementation("androidx.test.espresso:espresso-contrib:3.4.0")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.4.0")
    androidTestImplementation("androidx.test.espresso.idling:idling-concurrent:3.4.0")

    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
}