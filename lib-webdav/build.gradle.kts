plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlinx.kover")
    `java-library`
    `java-test-fixtures`
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(libs.coroutines.core)
    implementation(libs.okhttp)
    // FakeWebDavServer is shared with app modules' tests, including on-device ones.
    testFixturesApi(libs.okhttp.mockwebserver3)
    testFixturesApi(libs.okhttp.tls)
    testImplementation(libs.jupiter.api)
    testImplementation(libs.jupiter.params)
    testRuntimeOnly(libs.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:${libs.versions.jupiter.asProvider().get()}")
    testImplementation(libs.testcontainers)
    testImplementation(libs.coroutines.test)
}

tasks.test {
    useJUnitPlatform { excludeTags("webdav-server") }
    maxHeapSize = "1g"
}

val contractTest by tasks.registering(Test::class) {
    description = "Run the WebDAV operation contract against Nextcloud and Apache mod_dav (requires Docker)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("webdav-server") }
    maxHeapSize = "1g"
    shouldRunAfter(tasks.test)
    // A cached result cannot establish that the current Docker servers were exercised.
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Requires live WebDAV servers") { true }
}
