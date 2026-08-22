import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File
import java.io.FileInputStream
import java.util.Properties

val Project.projectConfig: ProjectConfig
    get() = extensions.findByType(ProjectConfig::class.java)!!

fun Project.setupRoomSchemas() {
    extensions.configure(com.google.devtools.ksp.gradle.KspExtension::class.java) {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

fun LibraryExtension.setupLibraryDefaults(
    projectConfig: ProjectConfig,
) {
    if (projectConfig.compileSdkPreview != null) {
        compileSdkPreview = projectConfig.compileSdkPreview
    } else {
        compileSdk = projectConfig.compileSdk
    }

    defaultConfig {
        minSdk = projectConfig.minSdk
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Every library carries the `version` dimension, even the ones with no flavor-specific source,
    // so the attribute propagates down from :app and each module resolves to a single variant.
    // The alternative (pinning consumers with missingDimensionStrategy) makes :app-common resolve
    // as gplay on :app's direct edge and as foss on every transitive one. Android Studio selects
    // one variant per module, so it cannot represent that and reports a variant selection conflict.
    flavorDimensions.add("version")
    productFlavors {
        create("foss") {
            dimension = "version"
            isDefault = true
        }
        create("gplay") {
            dimension = "version"
        }
    }
}

fun LibraryExtension.setupModuleBuildTypes() {
    buildTypes {
        debug {
            consumerProguardFiles("consumer-rules.pro")
        }
        create("beta") {
            consumerProguardFiles("consumer-rules.pro")
        }
        release {
            consumerProguardFiles("consumer-rules.pro")
        }
    }
}

fun Project.setupKotlinOptions() {
    tasks.withType(KotlinCompile::class.java) {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=kotlin.ExperimentalStdlibApi",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                "-opt-in=kotlinx.coroutines.FlowPreview",
                "-opt-in=kotlin.time.ExperimentalTime",
                "-jvm-default=no-compatibility",
                "-opt-in=kotlin.uuid.ExperimentalUuidApi",
            )
        }
    }
}

fun CommonExtension.setupCompileOptions() {
    compileOptions.isCoreLibraryDesugaringEnabled = true
    compileOptions.sourceCompatibility = JavaVersion.VERSION_17
    compileOptions.targetCompatibility = JavaVersion.VERSION_17
}

fun com.android.build.api.dsl.SigningConfig.setupCredentials(
    signingPropsPath: File? = null
) {

    val keyStoreFromEnv = System.getenv("STORE_PATH")?.let { File(it) }

    if (keyStoreFromEnv?.exists() == true) {
        println("Using signing data from environment variables.")
        storeFile = keyStoreFromEnv
        storePassword = System.getenv("STORE_PASSWORD")
        keyAlias = System.getenv("KEY_ALIAS")
        keyPassword = System.getenv("KEY_PASSWORD")
    } else {
        println("Using signing data from properties file.")
        val props = Properties().apply {
            signingPropsPath?.takeIf { it.canRead() }?.let { load(FileInputStream(it)) }
        }

        val keyStorePath = props.getProperty("release.storePath")?.let { File(it) }

        if (keyStorePath?.exists() == true) {
            storeFile = keyStorePath
            storePassword = props.getProperty("release.storePassword")
            keyAlias = props.getProperty("release.keyAlias")
            keyPassword = props.getProperty("release.keyPassword")
        }
    }
}

/**
 * Explicit test worker JVM configuration.
 *
 * Sized for CI (2-core runner, 4g Gradle daemon, org.gradle.workers.max=4), not for beefy dev
 * machines. Without this, workers run on Gradle's default -Xmx512m with no crash diagnostics.
 *
 * No MaxMetaspaceSize cap on purpose: Robolectric creates many classloaders and an arbitrary cap
 * would just trade one failure mode for another.
 */
fun Test.setupTestJvm() {
    maxHeapSize = "1g"
    maxParallelForks = 1

    // Unique per task so parallel test tasks cannot overwrite each other's diagnostics
    val crashDir = File(project.layout.buildDirectory.get().asFile, "test-jvm-crash/$name")
    doFirst { crashDir.mkdirs() }

    jvmArgs(
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=${crashDir.absolutePath}",
        "-XX:ErrorFile=${crashDir.absolutePath}/hs_err_pid%p.log",
    )
}

fun Test.setupTestLogging() {
    testLogging {
        events(
            TestLogEvent.FAILED,
            TestLogEvent.PASSED,
            TestLogEvent.SKIPPED,
//            TestLogEvent.STANDARD_OUT,
        )
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true

        addTestListener(object : TestListener {
            override fun beforeSuite(suite: TestDescriptor) {}
            override fun beforeTest(testDescriptor: TestDescriptor) {}
            override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
            override fun afterSuite(suite: TestDescriptor, result: TestResult) {
                // The root descriptor (parent == null) is the task-level result. It must be
                // reported too: a worker that dies abruptly may never fire afterSuite for its
                // children, so child-only reporting can print nothing at all for a crashed run.
                val label = if (suite.parent == null) "TASK RESULT" else "SUITE RESULT"
                val messages = """
                    ------------------------------------------------------------------------------------------------
                    | $label: ${result.resultType} ${result.testCount} tests: ${result.successfulTestCount} passed, ${result.failedTestCount} failed, ${result.skippedTestCount} skipped)
                    ------------------------------------------------------------------------------------------------

                """.trimIndent()
                println(messages)

                // Worker-death fingerprint: the task failed, yet not a single test case failed.
                if (suite.parent == null && result.resultType == TestResult.ResultType.FAILURE && result.failedTestCount == 0L) {
                    println(
                        """
                        ################################################################################################
                        # TEST JVM WORKER DEATH SUSPECTED
                        # The test task failed but zero test cases reported a failure (${result.skippedTestCount} skipped).
                        # That combination means the worker JVM died instead of the tests failing.
                        # Check the raw Gradle worker output above and build/test-jvm-crash/ for hs_err/heap dumps.
                        ################################################################################################

                        """.trimIndent()
                    )
                }
            }
        })
    }
}
