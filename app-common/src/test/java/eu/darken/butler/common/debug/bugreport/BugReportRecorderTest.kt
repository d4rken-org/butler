package eu.darken.butler.common.debug.bugreport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.ButlerId
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.upgrade.UpgradeDiagnostics
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.io.File
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BugReportRecorderTest : BaseTest() {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val storageLayout by lazy { BugReportStorageLayout(context) }
    private val recorderPathPublisher = RecorderPathPublisher()
    private val reportsDir get() = storageLayout.writeRoot
    private val privateReportsDir get() = File(context.filesDir, "bugreports")

    /**
     * Test-controlled clocks, handed to the recorder's two seams. The durations under test are
     * wall-clock/monotonic reads, so virtual time cannot drive them — and `SystemClock` throws on
     * the JVM, so the monotonic fake is mandatory for anything that reaches [BugReportRecorder.start].
     * Volatile: the seams are read on the recorder's coroutines, the fields written from the test.
     */
    private class TestClocks(
        @Volatile var wall: Instant = WALL_BASE,
        @Volatile var monotonic: Long = MONOTONIC_BASE,
    )

    private fun createRecorder(
        appScope: CoroutineScope,
        clocks: TestClocks,
        upgradeDiagnostics: Set<UpgradeDiagnostics> = emptySet(),
    ): BugReportRecorder = BugReportRecorder(
        context = context,
        appScope = appScope,
        dispatcherProvider = TestDispatcherProvider(),
        butlerId = ButlerId(context),
        json = Json { ignoreUnknownKeys = true },
        storageLayout = storageLayout,
        recorderPathPublisher = recorderPathPublisher,
        upgradeDiagnostics = upgradeDiagnostics,
    ).apply {
        wallClock = { clocks.wall }
        monotonicClock = { clocks.monotonic }
    }

    /**
     * Shared harness for every test that starts a recorder. The recorder is stopped in a nested
     * finally, BEFORE the scope goes: cancelling the scope alone does NOT uninstall a running
     * recorder's globally installed [eu.darken.butler.common.debug.logging.FileLogger], and several
     * cases deliberately end still recording. A leaked logger must fail THIS test rather than write
     * into every later one, so the installed-logger set is asserted and stragglers removed after.
     *
     * Real dispatchers: the diagnostics bound is a real-time timeout and the clock seams are what
     * make the durations deterministic. The envelope turns a wedged start or stop into a failure in
     * seconds instead of a held gradle worker.
     */
    private fun withRecorder(
        clocks: TestClocks = TestClocks(),
        upgradeDiagnostics: Set<UpgradeDiagnostics> = emptySet(),
        configure: BugReportRecorder.() -> Unit = {},
        block: suspend (BugReportRecorder) -> Unit,
    ) {
        val loggersBefore = Logging.loggers
        val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        var recorder: BugReportRecorder? = null
        try {
            try {
                val created = createRecorder(appScope, clocks, upgradeDiagnostics).apply(configure)
                recorder = created
                runBlocking(Dispatchers.IO) { withTimeout(BLOCK_TIMEOUT_MS) { block(created) } }
            } finally {
                recorder?.let {
                    runBlocking(Dispatchers.IO) { withTimeoutOrNull(STOP_TIMEOUT_MS) { it.forceStop() } }
                }
            }
        } finally {
            appScope.cancel()
            val leaked = Logging.loggers - loggersBefore.toSet()
            leaked.forEach { Logging.remove(it) }
            leaked shouldBe emptyList<Logging.Logger>()
        }
    }

    @Test
    fun `start creates meta, log and sentinel - forceStop clears sentinel`() = withRecorder { recorder ->
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
    fun `start publishes the report directory to the privileged helpers`() = withRecorder { recorder ->
        recorder.start()

        val id = recorder.state.value.recordingId!!
        recorderPathPublisher.path.value shouldBe File(reportsDir, id).path
    }

    @Test
    fun `stopping retracts the published path`() = withRecorder { recorder ->
        recorder.start()
        recorderPathPublisher.path.value shouldNotBe null

        recorder.forceStop()

        recorderPathPublisher.path.value shouldBe null
    }

    @Test
    fun `a start that cannot set up its files publishes no path`() {
        // A plain file where the report root belongs: the report directory cannot be created, so
        // start() rolls back before it would publish.
        reportsDir.parentFile!!.mkdirs()
        reportsDir.writeText("not a directory")

        withRecorder { recorder ->
            recorder.start()

            recorder.state.value.isRecording shouldBe false
            recorderPathPublisher.path.value shouldBe null
        }
    }

    @Test
    fun `requestStop is rejected before the minimum duration`() = withRecorder { recorder ->
        recorder.start()

        recorder.requestStop() shouldBe BugReportRecorder.StopResult.TooShort
        recorder.state.value.isRecording shouldBe true
    }

    @Test
    fun `an eight second recording warns`() {
        val clocks = TestClocks()
        withRecorder(clocks) { recorder ->
            recorder.start()

            clocks.monotonic += 8_000L
            recorder.requestStop() shouldBe BugReportRecorder.StopResult.TooShort
            recorder.state.value.isRecording shouldBe true

            // "Stop anyway" is the user's own next step, and past the threshold it stops cleanly.
            clocks.monotonic += 3_000L
            recorder.requestStop().shouldBeInstanceOf<BugReportRecorder.StopResult.Stopped>()
            recorder.state.value.isRecording shouldBe false
        }
    }

    @Test
    fun `a ten second recording stops`() {
        val clocks = TestClocks()
        withRecorder(clocks) { recorder ->
            recorder.start()

            clocks.monotonic += 10_000L

            recorder.requestStop().shouldBeInstanceOf<BugReportRecorder.StopResult.Stopped>()
            recorder.state.value.isRecording shouldBe false
        }
    }

    @Test
    fun `a forward wall-clock jump does not skip the warning`() {
        val clocks = TestClocks()
        withRecorder(clocks) { recorder ->
            recorder.start()

            // Three real seconds of recording, and a clock correction an hour forward. Wall-clock
            // measurement would call this a one-hour recording and skip the prompt.
            clocks.monotonic += 3_000L
            clocks.wall += 1.hours

            recorder.requestStop() shouldBe BugReportRecorder.StopResult.TooShort
            recorder.state.value.isRecording shouldBe true
        }
    }

    @Test
    fun `a backward wall-clock jump does not warn on a long recording`() {
        val clocks = TestClocks()
        withRecorder(clocks) { recorder ->
            recorder.start()

            // Twelve real seconds of recording, and an NTP sync that moves the wall clock an hour
            // back. Wall-clock measurement would report a negative duration here.
            clocks.monotonic += 12_000L
            clocks.wall -= 1.hours

            recorder.requestStop().shouldBeInstanceOf<BugReportRecorder.StopResult.Stopped>()
            recorder.state.value.isRecording shouldBe false
        }
    }

    @Test
    fun `slow diagnostics still count toward the duration`() {
        // The monotonic base is sampled BEFORE the file setup and the diagnostics loop, so time spent
        // inside start() is part of the recording's duration — as it always was under the wall clock.
        // The provider below stalls for real (well under the diagnostics bound) and, while stalled,
        // advances the fake monotonic clock by the 7s that stall stands for.
        val clocks = TestClocks()
        val slowProvider = object : UpgradeDiagnostics {
            override suspend fun debugInfo(): String {
                delay(1_000)
                clocks.monotonic += 7_000L
                return "slow-info"
            }
        }

        withRecorder(clocks, upgradeDiagnostics = setOf(slowProvider)) { recorder ->
            recorder.start()
            recorder.state.value.isRecording shouldBe true

            // Non-vacuous by construction: 3s since the state was committed is far short of the 10s
            // threshold, so this only stops if the base predates the setup (7s + 3s = 10s).
            clocks.monotonic += 3_000L
            recorder.requestStop().shouldBeInstanceOf<BugReportRecorder.StopResult.Stopped>()
            recorder.state.value.isRecording shouldBe false
        }
    }

    @Test
    fun `the recording id is published before the session infos are logged`() {
        // Deletion keys "active recording" on the published id, and the report directory is already
        // on disk while the session infos are being collected — a null id there is a deletable report.
        var started: BugReportRecorder? = null
        var idDuringDiagnostics: String? = null
        val provider = object : UpgradeDiagnostics {
            override suspend fun debugInfo(): String {
                idDuringDiagnostics = started!!.state.value.recordingId
                return "info"
            }
        }

        withRecorder(upgradeDiagnostics = setOf(provider)) { recorder ->
            started = recorder
            recorder.start()

            idDuringDiagnostics shouldNotBe null
            idDuringDiagnostics shouldBe recorder.state.value.recordingId
        }
    }

    @Test
    fun `force stop bypasses the duration threshold`() {
        // "Stop anyway" — the answer to the short-recording warning in the banner, the contact form
        // and the bug-report workspace dialog. It must stop a recording the threshold would reject.
        val clocks = TestClocks()
        withRecorder(clocks) { recorder ->
            recorder.start()

            clocks.monotonic += 2_000L
            recorder.requestStop() shouldBe BugReportRecorder.StopResult.TooShort

            recorder.forceStop() shouldNotBe null
            recorder.state.value.isRecording shouldBe false
        }
    }

    @Test
    fun `a failing diagnostics provider neither stops the recording nor its siblings`() {
        var siblingAsked = false
        withRecorder(
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
        ) { recorder ->
            recorder.start()

            recorder.state.value.isRecording shouldBe true
            siblingAsked shouldBe true
        }
    }

    @Test
    fun `a hanging diagnostics provider is reported as wedged and does not stop the recording`() {
        var siblingAsked = false
        withRecorder(
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
            configure = { diagnosticsTimeout = 300.milliseconds },
        ) { recorder ->
            val capture = RecordingLogger()
            Logging.install(capture)
            val elapsed = try {
                // Wall-clock watchdog: an unbounded read would hang this test instead of failing.
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
        }
    }

    @Test
    fun `a provider with nothing to report stays quiet instead of warning`() {
        // The FOSS case: no diagnostics contributed is normal, not a failure. A warning here would
        // send every FOSS debug log out looking like a wedged billing read.
        withRecorder(
            upgradeDiagnostics = setOf(
                object : UpgradeDiagnostics {
                    override suspend fun debugInfo(): String? = null
                },
            ),
        ) { recorder ->
            val capture = RecordingLogger()
            Logging.install(capture)
            try {
                recorder.start()
            } finally {
                Logging.remove(capture)
            }

            capture.messages().any { it.contains("No upgrade diagnostics from") } shouldBe true
            capture.warnings().any { it.contains("Upgrade diagnostics unavailable") } shouldBe false
        }
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

        // No recorder is started here, so this needs neither the clock fakes nor the leak harness.
        createRecorder(CoroutineScope(Dispatchers.Unconfined), TestClocks()).sweepOrphanedSentinels()

        interrupted.exists() shouldBe true
        File(interrupted, ".recording").exists() shouldBe false
        incomplete.exists() shouldBe false
    }

    @Test
    fun `sweep finalizes an interrupted recording in the legacy root`() = runTest {
        // Written by a version that still recorded into filesDir: deleteAll() skips a directory with a
        // sentinel, so a sweep that only looks at the write root would leave this one undeletable.
        val interrupted = File(privateReportsDir, "recording_5_eeee").apply { mkdirs() }
        File(interrupted, "meta.json").writeText("{}")
        File(interrupted, "report.log").writeText("x")
        File(interrupted, ".recording").createNewFile()

        createRecorder(CoroutineScope(Dispatchers.Unconfined), TestClocks()).sweepOrphanedSentinels()

        interrupted.exists() shouldBe true
        File(interrupted, ".recording").exists() shouldBe false
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

    companion object {
        // Independent of any production bound: a wedged wait has to fail the test, not hang the
        // gradle worker.
        private const val BLOCK_TIMEOUT_MS = 20_000L
        private const val STOP_TIMEOUT_MS = 10_000L
        private val WALL_BASE = Instant.fromEpochMilliseconds(1_800_000_000_000L)
        private const val MONOTONIC_BASE = 100_000L
    }
}
