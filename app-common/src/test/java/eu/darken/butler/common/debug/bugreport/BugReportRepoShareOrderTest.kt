package eu.darken.butler.common.debug.bugreport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.ButlerId
import eu.darken.butler.common.debug.logging.RingLogBuffer
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.io.File
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The share guard ([BugReportRecorder.isActiveOrStarting]) takes the recorder mutex and therefore
 * waits out startup recovery of an interrupted recording. Storage resolution must not run before it,
 * or the share path picks the directory to package from a view of the store recovery is still
 * allowed to change.
 *
 * The order is observed through the two collaborators the repo takes as constructor dependencies, so
 * the assertion is deterministic rather than timing-dependent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BugReportRepoShareOrderTest : BaseTest() {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val realLayout by lazy { BugReportStorageLayout(context) }
    private val reportsDir get() = realLayout.writeRoot
    private val json = Json { ignoreUnknownKeys = true }
    private val recorderState = MutableStateFlow(BugReportRecorder.State())

    /** Call trail across the two collaborators, in the order the repo invoked them. */
    private val calls = mutableListOf<String>()

    private fun createRepo(): BugReportRepo {
        val recorder = mockk<BugReportRecorder>(relaxed = true) {
            every { state } returns recorderState
            coEvery { isActiveOrStarting(any()) } answers {
                calls += "guard"
                firstArg<String>() == recorderState.value.recordingId
            }
        }
        // Delegates to the real layout so the repo still sees the report written below; the stubs
        // exist only to timestamp the storage reads.
        val layout = mockk<BugReportStorageLayout> {
            every { roots } returns realLayout.roots
            every { writeRoot } returns realLayout.writeRoot
            every { findReportDir(any()) } answers { realLayout.findReportDir(firstArg()) }
            every { allReportDirs(any()) } answers {
                calls += "resolve"
                realLayout.allReportDirs(firstArg())
            }
        }
        return BugReportRepo(
            context = context,
            appScope = CoroutineScope(Dispatchers.Unconfined),
            dispatcherProvider = TestDispatcherProvider(),
            ringLogBuffer = RingLogBuffer(),
            bugReportRecorder = recorder,
            butlerId = ButlerId(context),
            json = json,
            storageLayout = layout,
        )
    }

    private fun writeReportDir(
        id: String,
        type: BugReport.Type = BugReport.Type.REPORTED,
        createdAt: Instant = Clock.System.now(),
    ) {
        val dir = File(reportsDir, id).apply { mkdirs() }
        val report = BugReport(
            id = id,
            createdAt = createdAt,
            type = type,
            appVersion = "1.0",
            deviceFingerprint = "fp",
            apiLevel = "29",
            flavor = "FOSS",
            buildType = "DEBUG",
            installId = "iid",
            locale = "en",
        )
        File(dir, BugReportStorage.META_FILE).writeText(json.encodeToString(BugReport.serializer(), report))
        File(dir, BugReportStorage.LOG_FILE).writeText("rec log")
    }

    @Test
    fun `buildShareZip consults the recorder guard before resolving the report from storage`() = runTest {
        val repo = createRepo()
        writeReportDir("order_1")
        // The repo's init cleanup already scanned the store; only the share call is under test.
        calls.clear()

        repo.buildShareZip("order_1")

        withClue("call order was $calls") {
            // Both collaborators were reached, so the ordering assertion below is not vacuous.
            calls shouldContain "resolve"
            calls.first() shouldBe "guard"
        }
    }
}
