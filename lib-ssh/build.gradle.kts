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
    implementation(libs.sshd.core)
    implementation(libs.sshd.sftp)
    implementation(libs.bouncycastle)
    // MINA decrypts encrypted PKCS#8 keys through bcpkix.
    implementation(libs.bouncycastle.pkix)
    // The sshj connector is a test-only oracle for the contract suites.
    testImplementation(libs.sshj)
    testImplementation(libs.jupiter.api)
    testImplementation(libs.jupiter.params)
    testRuntimeOnly(libs.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:${libs.versions.jupiter.asProvider().get()}")
    testImplementation(libs.testcontainers)
    testImplementation(libs.coroutines.test)
}

tasks.test {
    useJUnitPlatform { excludeTags("ssh-server", "ssh-perf", "sftp-embedded") }
    maxHeapSize = "1g"
}

val contractTest by tasks.registering(Test::class) {
    description = "Run SFTP contracts and perf scenarios against OpenSSH (Docker) and an embedded MINA server."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("ssh-server", "ssh-perf", "sftp-embedded") }
    systemProperty("ssh.perf.reportDir", layout.buildDirectory.dir("reports/ssh-perf").get().asFile.absolutePath)
    maxHeapSize = "1g"
    shouldRunAfter(tasks.test)
    // A cached result cannot establish that the current Docker server was exercised.
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Requires a live SSH server") { true }
}
