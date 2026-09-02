package eu.darken.butler.bugreport.ui

import eu.darken.butler.bugreport.ui.BugReportWorkspaceViewModel.LogState
import eu.darken.butler.common.debug.bugreport.BugReport
import eu.darken.butler.common.debug.bugreport.BugReportInfo
import eu.darken.butler.common.debug.bugreport.BugReportRecorder
import eu.darken.butler.common.debug.bugreport.BugReportRepo
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.io.IOException
import kotlin.time.Instant

/**
 * Opening a report must not touch the log file: the tail is read the first time the user expands the
 * section, is kept across collapse/expand cycles, and is dropped again when another report is opened.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BugReportWorkspaceViewModelLogTest : BaseTest() {

    private val tail = BugReportRepo.LogTail(lines = listOf("line"), totalLines = 1)
    private val loaded = LogState.Loaded(lines = listOf("line"), totalLines = 1, shownLines = 1, isTruncated = false)

    private fun info(id: String) = BugReportInfo(
        report = BugReport(
            id = id,
            createdAt = Instant.parse("2026-06-15T10:00:00Z"),
            type = BugReport.Type.RECORDING,
            errorClass = null,
            errorMessage = null,
            stackTrace = null,
            threadName = null,
            appVersion = "v0.0.0-beta1",
            deviceFingerprint = "Pixel/foo",
            apiLevel = "36",
            flavor = "FOSS",
            buildType = "RELEASE",
            installId = "abc",
            locale = "en-US",
        ),
        isSeen = true,
        logSizeBytes = 4_096L,
    )

    // The detail is derived from the report list, so an unstubbed reports flow would never produce
    // one; a relaxed readLogTail would report 0 total lines and map to LogState.Empty.
    private val repo = mockk<BugReportRepo>(relaxed = true).apply {
        every { reports } returns flowOf(listOf(info("a"), info("b")))
        coEvery { readLogTail(any(), any()) } returns tail
    }
    private val recorder = mockk<BugReportRecorder>(relaxed = true).apply {
        every { state } returns MutableStateFlow(BugReportRecorder.State())
    }

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    // The state flow is WhileSubscribed(5000): without a live collector nothing runs at all, and
    // "the log was never read" would hold for an eager implementation too.
    private fun TestScope.createVM(): BugReportWorkspaceViewModel {
        val vm = BugReportWorkspaceViewModel(
            id = Workspace.Id(),
            dispatchers = TestDispatcherProvider(),
            bugReportRepo = repo,
            bugReportRecorder = recorder,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        return vm
    }

    private suspend fun BugReportWorkspaceViewModel.detail() = state.first()!!.detail!!

    @Test
    fun `opening a report reads nothing from disk`() = runTest {
        val vm = createVM()

        vm.openReport("a")
        advanceUntilIdle()

        coVerify(exactly = 0) { repo.readLogTail(any(), any()) }
        vm.detail().logState shouldBe LogState.Idle
        vm.detail().isLogExpanded shouldBe false
    }

    @Test
    fun `expanding the section loads the tail`() = runTest {
        val vm = createVM()

        vm.openReport("a")
        vm.setLogExpanded(true)
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.readLogTail("a", any()) }
        vm.detail().logState shouldBe loaded
        vm.detail().isLogExpanded shouldBe true
    }

    @Test
    fun `re-expanding a loaded section does not read again`() = runTest {
        val vm = createVM()

        vm.openReport("a")
        vm.setLogExpanded(true)
        vm.setLogExpanded(false)
        vm.setLogExpanded(true)
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.readLogTail("a", any()) }
        vm.detail().logState shouldBe loaded
    }

    @Test
    fun `switching reports resets the section instead of loading the new log`() = runTest {
        val vm = createVM()

        vm.openReport("a")
        vm.setLogExpanded(true)
        advanceUntilIdle()

        vm.openReport("b")
        advanceUntilIdle()

        coVerify(exactly = 0) { repo.readLogTail("b", any()) }
        vm.detail().logState shouldBe LogState.Idle
        vm.detail().isLogExpanded shouldBe false
    }

    @Test
    fun `re-expanding after a failed read retries`() = runTest {
        coEvery { repo.readLogTail(any(), any()) } throws IOException("nope")
        val vm = createVM()

        vm.openReport("a")
        vm.setLogExpanded(true)
        advanceUntilIdle()
        vm.detail().logState shouldBe LogState.Error

        vm.setLogExpanded(false)
        coEvery { repo.readLogTail(any(), any()) } returns tail
        vm.setLogExpanded(true)
        advanceUntilIdle()

        coVerify(exactly = 2) { repo.readLogTail("a", any()) }
        vm.detail().logState shouldBe loaded
    }
}
