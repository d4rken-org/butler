package eu.darken.butler.common.debug.bugreport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.ButlerId
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.upgrade.UpgradeDiagnostics
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.io.File
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BugReportRecorderTest : BaseTest() {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val reportsDir get() = File(context.filesDir, "bugreports")

    private fun createRecorder(
        upgradeDiagnostics: Set<UpgradeDiagnostics> = emptySet(),
    ): BugReportRecorder = BugReportRecorder(
        context = context,
        appScope = CoroutineScope(Dispatchers.Unconfined),
        dispatcherProvider = TestDispatcherProvider(),
        butlerId = ButlerId(context),
        json = Json { ignoreUnknownKeys = true },
        upgradeDiagnostics = upgradeDiagnostics,
    )

    @Test
    fun `start creates meta, log and sentinel - forceStop clears sentinel`() = runTest {
        val recorder = createRecorder()
        recorder.start()

        val state = recorder.state.value
        state.isRecording shouldBe true
        val id = state.recordingId!!
        val dir = File(reportsDir, id)
        File(dir, "meta.json").exists() shouldBe true
        File(dir, "report.log").exists() shouldBe true
        File(dir, ".recording").exists() shouldBe true

        recorder.forceStop()

        recorder.state.value.isRecording shouldBe false
        File(dir, ".recording").exists() shouldBe false
        File(dir, "meta.json").exists() shouldBe true
        File(dir, "report.log").exists() shouldBe true
    }

    @Test
    fun `requestStop is rejected before the minimum duration`() = runTest {
        val recorder = createRecorder()
        recorder.start()

        recorder.requestStop() shouldBe BugReportRecorder.StopResult.TooShort
        recorder.state.value.isRecording shouldBe true

        recorder.forceStop()
    }

    @Test
    fun `a failing diagnostics provider neither stops the recording nor its siblings`() = runTest {
        var siblingAsked = false
        val recorder = createRecorder(
            upgradeDiagnostics = setOf(
                object : UpgradeDiagnostics {
                    override suspend fun debugInfo(): String = throw IllegalStateException("nope")
                },
                object : UpgradeDiagnostics {
                    override suspend fun debugInfo(): String {
                        siblingAsked = true
                        return "sibling-info"
                    }
                },
            ),
        )

        recorder.start()

        recorder.state.value.isRecording shouldBe true
        siblingAsked shouldBe true

        recorder.forceStop()
    }

    @Test
    fun `a hanging diagnostics provider is reported as wedged and does not stop the recording`() = runTest {
        // Real dispatchers on purpose: the bound is a wall-clock timeout, under virtual time it
        // would fire instantly while nothing else is scheduled and prove nothing.
        withContext(Dispatchers.IO) {
            var siblingAsked = false
            val recorder = createRecorder(
                upgradeDiagnostics = setOf(
                    object : UpgradeDiagnostics {
                        override suspend fun debugInfo(): String = awaitCancellation()
                    },
                    object : UpgradeDiagnostics {
                        override suspend fun debugInfo(): String {
                            siblingAsked = true
                            return "sibling-info"
                        }
                    },
                ),
            ).apply { diagnosticsTimeout = 300.milliseconds }

            val capture = RecordingLogger()
            Logging.install(capture)
            val elapsed = try {
                // Wall-clock watchdog: an unbounded read would hang this test forever instead of failing.
                measureTimeMillis { withTimeout(10_000) { recorder.start() } }
            } finally {
                Logging.remove(capture)
            }

            elapsed shouldBeLessThan 3_000L
            // "Never answered" must be told apart from "nothing to report" — the quiet line would
            // read as a FOSS build with no billing stack at all.
            capture.warnings().any { it.contains("read did not finish within") } shouldBe true
            siblingAsked shouldBe true
            recorder.state.value.isRecording shouldBe true

            recorder.forceStop()
        }
    }

    @Test
    fun `a provider with nothing to report stays quiet instead of warning`() = runTest {
        // The FOSS case: no diagnostics contributed is normal, not a failure. A warning here would
        // send every FOSS debug log out looking like a wedged billing read.
        val recorder = createRecorder(
            upgradeDiagnostics = setOf(
                object : UpgradeDiagnostics {
                    override suspend fun debugInfo(): String? = null
                },
            ),
        )

        val capture = RecordingLogger()
        Logging.install(capture)
        try {
            recorder.start()
        } finally {
            Logging.remove(capture)
        }

        capture.messages().any { it.contains("No upgrade diagnostics from") } shouldBe true
        capture.warnings().any { it.contains("Upgrade diagnostics unavailable") } shouldBe false

        recorder.forceStop()
    }

    @Test
    fun `sweep finalizes an interrupted recording and drops an incomplete one`() = runTest {
        // Interrupted-but-complete: meta + log + dangling sentinel.
        val interrupted = File(reportsDir, "recording_1_aaaa").apply { mkdirs() }
        File(interrupted, "meta.json").writeText("{}")
        File(interrupted, "report.log").writeText("x")
        File(interrupted, ".recording").createNewFile()

        // Incomplete: meta written but log never created.
        val incomplete = File(reportsDir, "recording_2_bbbb").apply { mkdirs() }
        File(incomplete, "meta.json").writeText("{}")

        createRecorder().sweepOrphanedSentinels()

        interrupted.exists() shouldBe true
        File(interrupted, ".recording").exists() shouldBe false
        incomplete.exists() shouldBe false
    }

    private class RecordingLogger : Logging.Logger {
        private val lines = mutableListOf<Pair<Logging.Priority, String>>()

        override fun log(priority: Logging.Priority, tag: String, message: String, metaData: Map<String, Any>?) {
            synchronized(lines) { lines.add(priority to message) }
        }

        fun messages(): List<String> = synchronized(lines) { lines.map { it.second } }

        fun warnings(): List<String> = synchronized(lines) {
            lines.filter { it.first == Logging.Priority.WARN }.map { it.second }
        }
    }
}
