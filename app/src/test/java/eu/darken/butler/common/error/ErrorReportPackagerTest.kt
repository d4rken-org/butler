package eu.darken.butler.common.error

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.ButlerId
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.serialization.SerializationCommonModule
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.util.zip.ZipFile
import kotlin.time.Instant

/**
 * Lives in :app because [androidx.core.content.FileProvider] resolves against the application's
 * provider authority and its path XML, which only the app manifest declares.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ErrorReportPackagerTest : BaseTest() {

    private lateinit var application: Application
    private lateinit var packager: ErrorReportPackager
    private lateinit var reportsDir: File

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()
        reportsDir = File(application.cacheDir, ErrorReportPackager.REPORTS_DIR)
        reportsDir.deleteRecursively()
        packager = ErrorReportPackager(
            context = application,
            butlerId = ButlerId(application),
            json = SerializationCommonModule().json(),
            dispatcherProvider = TestDispatcherProvider(),
        )
    }

    private fun incident(
        id: String = "abcd1234",
        error: Throwable = IOException("boom"),
        logFile: File? = null,
    ) = ErrorIncident(
        incidentId = id,
        occurredAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        occurredAtIsApproximate = false,
        error = error,
        context = mapOf("nav.target" to "Home"),
        logFile = logFile,
    )

    private fun spooledLog(text: String = "log line one\nlog line two"): File =
        File.createTempFile("incident", ".log").apply {
            deleteOnExit()
            writeText(text)
        }

    private fun zipEntries(zip: File): List<String> =
        ZipFile(zip).use { archive -> archive.entries().toList().map { it.name } }

    private fun readEntry(zip: File, name: String): String =
        ZipFile(zip).use { archive -> archive.getInputStream(archive.getEntry(name)).reader().readText() }

    @Test
    fun `the report holds the trace, the payload and the log trail`() = runTest {
        val packaged = packager.packageReport(incident(logFile = spooledLog()), summary = null)

        val zip = File(reportsDir, "abcd1234.zip")
        zip.exists() shouldBe true
        zipEntries(zip) shouldContainExactlyInAnyOrder listOf(
            ErrorReportPackager.STACKTRACE_FILE,
            ErrorReportPackager.REPORT_FILE,
            ErrorReportPackager.LOG_FILE,
        )
        readEntry(zip, ErrorReportPackager.LOG_FILE) shouldBe "log line one\nlog line two"
        packaged.payload.incidentId shouldBe "abcd1234"
    }

    @Test
    fun `the trace is stored verbatim, map-id frames included`() = runTest {
        val error = IOException("boom").apply {
            stackTrace = arrayOf(
                StackTraceElement("eu.darken.butler.Foo", "bar", "SourceFile ~[r8-map-id-${"a".repeat(64)}]", 1),
                StackTraceElement("eu.darken.butler.Foo", "baz", "SourceFile ~[r8-map-id-${"a".repeat(64)}]", 2),
            )
        }

        packager.packageReport(incident(error = error), summary = null)

        val zip = File(reportsDir, "abcd1234.zip")
        readEntry(zip, ErrorReportPackager.STACKTRACE_FILE) shouldBe error.asLog()
    }

    @Test
    fun `the map id is recorded once in the payload`() = runTest {
        val hash = "b".repeat(64)
        val error = IOException("boom").apply {
            stackTrace = Array(40) {
                StackTraceElement("eu.darken.butler.Foo", "bar", "SourceFile ~[r8-map-id-$hash]", it)
            }
        }

        val packaged = packager.packageReport(incident(error = error), summary = null)

        packaged.payload.app.mapId shouldBe hash
    }

    @Test
    fun `the summary reaches the payload on disk`() = runTest {
        packager.packageReport(incident(), summary = "Copy 3 files\nTo /storage/emulated/0")

        val decoded = SerializationCommonModule().json().decodeFromString(
            ErrorReportPayload.serializer(),
            readEntry(File(reportsDir, "abcd1234.zip"), ErrorReportPackager.REPORT_FILE),
        )
        decoded.summary shouldBe "Copy 3 files\nTo /storage/emulated/0"
        decoded.context["nav.target"] shouldBe "Home"
    }

    @Test
    fun `an incident without a spooled log still produces a report`() = runTest {
        packager.packageReport(incident(logFile = null), summary = null)

        val zip = File(reportsDir, "abcd1234.zip")
        zipEntries(zip).size shouldBe 3
        readEntry(zip, ErrorReportPackager.LOG_FILE).isNotEmpty() shouldBe true
    }

    @Test
    fun `a sixth report drops the oldest of the five kept`() = runTest {
        reportsDir.mkdirs()
        val existing = (1..5).map { index ->
            File(reportsDir, "old$index.zip").apply {
                writeText("stale")
                setLastModified(1_000L * index)
            }
        }

        packager.packageReport(incident(id = "newest01"), summary = null)

        reportsDir.listFiles()!!.map { it.name } shouldContainExactlyInAnyOrder listOf(
            existing[1].name,
            existing[2].name,
            existing[3].name,
            existing[4].name,
            "newest01.zip",
        )
    }

    @Test
    fun `a failure while staging leaves no report under the retained name`() = runTest {
        val unreadable = spooledLog().apply { setReadable(false, false) }
        assumeTrue("Cannot make a file unreadable as this user", !unreadable.canRead())

        shouldThrowAny {
            packager.packageReport(incident(logFile = unreadable), summary = null)
        }

        File(reportsDir, "abcd1234.zip").exists() shouldBe false
        reportsDir.listFiles().orEmpty().map { it.name } shouldBe emptyList<String>()
    }

    @Test
    fun `a failure while building the payload leaves no report either`() = runTest {
        val hostile = object : IOException("boom") {
            override fun printStackTrace(writer: PrintWriter) {
                throw IllegalStateException("no trace for you")
            }
        }

        shouldThrowAny {
            packager.packageReport(incident(error = hostile), summary = null)
        }

        File(reportsDir, "abcd1234.zip").exists() shouldBe false
    }
}
