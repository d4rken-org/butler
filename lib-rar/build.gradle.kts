import java.time.Duration

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
    testImplementation(libs.jupiter.api)
    testImplementation(libs.jupiter.params)
    testImplementation(libs.jazzer.junit)
    testRuntimeOnly(libs.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:${libs.versions.jupiter.asProvider().get()}")
}

tasks.test {
    // Benchmarks and exhaustive sweeps get their own Test tasks. @FuzzTest methods only replay their
    // seeds here: Jazzer fuzzes only with JAZZER_FUZZ set, which rarFuzz does.
    useJUnitPlatform { excludeTags("rar-perf", "rar-sweep") }
    // Small heap and a hard timeout: a hostile input that hangs or over-allocates fails the build.
    maxHeapSize = "384m"
    timeout.set(Duration.ofMinutes(15))
}

val rarSweep by tasks.registering(Test::class) {
    description = "Run the hostile-input sweeps too slow for the test task."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("rar-sweep") }
    maxHeapSize = "384m"
    timeout.set(Duration.ofMinutes(60))
    shouldRunAfter(tasks.test)
}

val rarFuzz by tasks.registering(Test::class) {
    description = "Fuzz the reader with Jazzer for -Prar.fuzz.duration (default 60s); findings land in build/reports/rar-fuzz."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    // Jazzer fuzzes one @FuzzTest per JVM; `test` replays the seeds of the others.
    filter { includeTestsMatching("eu.darken.rar.RarReaderFuzzTest") }
    environment("JAZZER_FUZZ", "1")
    // Overrides @FuzzTest(maxDuration).
    systemProperty("jazzer.max_duration", providers.gradleProperty("rar.fuzz.duration").getOrElse("60s"))
    // Jazzer writes its generated corpus, crash files and reproducers below the working directory.
    val fuzzDir = layout.buildDirectory.dir("reports/rar-fuzz").get().asFile
    workingDir = fuzzDir
    maxHeapSize = "1g"
    shouldRunAfter(tasks.test)
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Fuzzing explores new inputs on every run") { true }
    doFirst {
        // The generated corpus carries over between runs; findings of an earlier run do not.
        fuzzDir.mkdirs()
        fuzzDir.listFiles().orEmpty().filter { it.name != ".cifuzz-corpus" }.forEach { it.deleteRecursively() }
    }
    doLast {
        val findings = fuzzDir.listFiles().orEmpty()
            .filter { file -> listOf("crash-", "timeout-", "oom-", "leak-", "Crash_").any { file.name.startsWith(it) } }
        if (findings.isNotEmpty()) throw GradleException("Jazzer findings: ${findings.joinToString { it.path }}")
    }
}

val rarPerf by tasks.registering(Test::class) {
    description = "Run the decoding benchmarks; results land in build/reports/rar-perf/rar.json."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("rar-perf") }
    systemProperty("rar.perf.reportDir", layout.buildDirectory.dir("reports/rar-perf").get().asFile.absolutePath)
    maxHeapSize = "1g"
    shouldRunAfter(tasks.test)
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Benchmark results depend on the machine and its load") { true }
}

kover {
    currentProject {
        instrumentation {
            // Coverage comes from `test` alone; fuzzing, benchmark and sweep runs must never feed it.
            disabledForTestTasks.addAll("rarFuzz", "rarPerf", "rarSweep")
        }
    }
}
