plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlinx.kover")
    `java-library`
}

kotlin {
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
    implementation(libs.bouncycastle)
    testImplementation(libs.smbj)
    testImplementation(libs.jupiter.api)
    testImplementation(libs.jupiter.params)
    testRuntimeOnly(libs.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:${libs.versions.jupiter.asProvider().get()}")
    testImplementation(libs.testcontainers)
    testImplementation(libs.coroutines.test)
}

tasks.test {
    useJUnitPlatform { excludeTags("smb-server") }
    maxHeapSize = "1g"
}

val contractTest by tasks.registering(Test::class) {
    description = "Run shared SMBJ/Kotlin behavior contracts against isolated Samba servers (requires Docker)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("smb-server") }
    maxHeapSize = "1g"
    shouldRunAfter(tasks.test)
    // A cached result cannot establish that the current Docker server was exercised.
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Requires a live SMB server") { true }
}
