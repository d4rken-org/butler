package eu.darken.butler.common.debug.bugreport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.ButlerId
import eu.darken.butler.common.debug.logging.RingLogBuffer
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BugReportRepoTest : BaseTest() {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val reportsDir get() = File(context.filesDir, "bugreports")

    private fun createRepo(): BugReportRepo {
        val buffer = RingLogBuffer().apply {
            log(eu.darken.butler.common.debug.logging.Logging.Priority.INFO, "Test", "log line", null)
        }
        return BugReportRepo(
            context = context,
            appScope = CoroutineScope(Dispatchers.Unconfined),
            dispatcherProvider = TestDispatcherProvider(),
            ringLogBuffer = buffer,
            butlerId = ButlerId(context),
            json = Json { ignoreUnknownKeys = true },
        )
    }

    @Test
    fun `captureCrashBlocking stores a crash report with log`() = runTest {
        val repo = createRepo()
        repo.captureCrashBlocking(IllegalStateException("boom"), Thread.currentThread())

        val reports = repo.reports.first()
        reports shouldHaveSize 1
        val info = reports.single()
        info.report.type shouldBe BugReport.Type.CRASH
        info.report.errorClass shouldBe "java.lang.IllegalStateException"
        info.report.errorMessage shouldBe "boom"
        info.isSeen shouldBe false

        repo.readLog(info.id) shouldContain "log line"
        repo.hasUnseenCrashes.first() shouldBe true
    }

    @Test
    fun `markSeen clears the unseen flag`() = runTest {
        val repo = createRepo()
        repo.captureCrashBlocking(IllegalStateException("boom"), Thread.currentThread())
        val id = repo.reports.first().single().id

        repo.markSeen(id)

        repo.reports.first().single().isSeen shouldBe true
        repo.hasUnseenCrashes.first() shouldBe false
    }

    @Test
    fun `delete removes a report`() = runTest {
        val repo = createRepo()
        repo.captureCrashBlocking(IllegalStateException("boom"), Thread.currentThread())
        val id = repo.reports.first().single().id

        repo.delete(id)

        repo.reports.first() shouldHaveSize 0
    }

    @Test
    fun `scan skips incomplete and corrupt directories`() = runTest {
        val repo = createRepo()
        repo.captureCrashBlocking(IllegalStateException("boom"), Thread.currentThread())

        // Incomplete dir (no meta.json), a temp dir, and a corrupt meta.json.
        File(reportsDir, "incomplete").mkdirs()
        File(reportsDir, ".tmp-partial").mkdirs()
        File(reportsDir, "corrupt").mkdirs()
        File(File(reportsDir, "corrupt"), "meta.json").writeText("{ not valid json")

        repo.reports.first() shouldHaveSize 1
    }

    @Test
    fun `retention keeps only the newest reports`() = runTest {
        val repo = createRepo()
        repeat(30) { repo.captureReport(IllegalStateException("e$it")) }

        repo.reports.first() shouldHaveSize 25
    }
}
