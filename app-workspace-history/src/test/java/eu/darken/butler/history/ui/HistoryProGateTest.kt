package eu.darken.butler.history.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.navigation.DestinationUpgrade
import eu.darken.butler.common.navigation.NavEvent
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.history.HistoryEntry
import eu.darken.butler.workspace.core.operations.history.HistoryOutcome
import eu.darken.butler.workspace.core.operations.history.HistorySettings
import eu.darken.butler.workspace.core.operations.history.OperationHistoryRepo
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Sharing and deleting history entries are Pro features. The gate suspends, so it also has to cope
 * with the selection or the detail sheet moving on while it is in flight.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryProGateTest : BaseTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val application = context as Application
    private val completedAt = Instant.parse("2026-09-02T14:31:05Z")

    private val workspaceProvider = mockk<WorkspaceProvider>().apply {
        every { retrieve(any()) } returns flowOf(null)
    }
    private val historySettings = mockk<HistorySettings>(relaxed = true)
    private val historyRepo = mockk<OperationHistoryRepo>(relaxed = true).apply {
        every { observeCount() } returns flowOf(0)
    }

    private class FakeInfo(
        override val isPro: Boolean,
        override val isSettled: Boolean,
        override val error: Throwable? = null,
    ) : UpgradeRepo.Info {
        override val type = UpgradeRepo.Type.FOSS
        override val upgradedAt: Instant? = null
    }

    /**
     * Backed by a MutableStateFlow, like the production repos. A cold single-element flow would end
     * `isProForUi`'s wait in a NoSuchElementException, which its catch turns into an allow - every
     * unsettled case would then pass through the fake instead of through the gate.
     */
    private class FakeUpgradeRepo(
        pro: Boolean,
        settled: Boolean = true,
        error: Throwable? = null,
    ) : UpgradeRepo {
        private val infoFlow = MutableStateFlow<UpgradeRepo.Info>(FakeInfo(pro, settled, error))

        override val storeSite = ""
        override val upgradeSite = ""
        override val betaSite = ""
        override val upgradeInfo: Flow<UpgradeRepo.Info> = infoFlow

        override suspend fun refresh() = Unit

        fun settle(pro: Boolean) {
            infoFlow.value = FakeInfo(pro, isSettled = true)
        }
    }

    /** Reports no changes, so a share from the sheet falls back to the attempted paths query. */
    private val entry = HistoryEntry(
        id = "entry-1",
        kind = Operation.Metadata.Kind.DELETE,
        intent = null,
        originType = HistoryEntry.OriginType.EXPLORER,
        originWorkspaceId = "ws",
        title = "Delete 2 items",
        description = "2 items in /sdcard/ButlerQA",
        summary = null,
        startedAt = completedAt - 1200.milliseconds,
        completedAt = completedAt,
        duration = 1200.milliseconds,
        outcome = HistoryOutcome.FAILED,
        errorMessage = "Permission denied",
        errorClass = null,
        affectedPathsCount = 0,
        partialErrorCount = 0,
        pathsTruncated = false,
        paths = emptyList(),
    )

    /** A second selected entry, so a partial deselection can be distinguished from a full one. */
    private val entry2 = entry.copy(id = "entry-2", title = "Delete 1 item")

    private val attempted = OperationHistoryRepo.AttemptedPaths(
        paths = listOf("/sdcard/ButlerQA", "/sdcard/ButlerQA/notes.txt"),
        totalCount = 2,
    )

    private fun createVM(
        upgradeRepo: UpgradeRepo,
        dispatchers: DispatcherProvider = TestDispatcherProvider(),
    ) = HistoryWorkspaceViewModel(
        id = Workspace.Id(),
        context = context,
        dispatchers = dispatchers,
        workspaceProvider = workspaceProvider,
        historyRepo = historyRepo,
        historySettings = historySettings,
        upgradeRepo = upgradeRepo,
    )

    private fun startedChooser(): Intent? = shadowOf(application).nextStartedActivity

    @Test
    fun `a free user gets the upgrade prompt instead of sharing the selection`() {
        val vm = createVM(FakeUpgradeRepo(pro = false))
        vm.setSelection(setOf(entry.id))

        vm.onActionClick(HistoryActionBarItem.Share(listOf(entry)))

        vm.overlayState.value.proPromptOpen shouldBe true
        startedChooser() shouldBe null
    }

    @Test
    fun `a free user gets the upgrade prompt instead of the delete confirmation`() {
        val vm = createVM(FakeUpgradeRepo(pro = false))
        vm.setSelection(setOf(entry.id))

        vm.onActionClick(HistoryActionBarItem.Delete(listOf(entry)))

        vm.overlayState.value.proPromptOpen shouldBe true
        vm.overlayState.value.deleteConfirmEntries shouldBe emptyList()
    }

    @Test
    fun `the sheet's share is gated before the attempted paths are queried`() {
        coEvery { historyRepo.getAttemptedPaths(entry.id) } returns attempted
        val vm = createVM(FakeUpgradeRepo(pro = false))

        // Opening the sheet issues the one query this scenario expects; a gate placed after the
        // query would add a second one.
        vm.showEntryDetails(entry)
        coVerify(exactly = 1) { historyRepo.getAttemptedPaths(entry.id) }

        vm.shareEntry(entry)

        vm.overlayState.value.proPromptOpen shouldBe true
        coVerify(exactly = 1) { historyRepo.getAttemptedPaths(entry.id) }
        startedChooser() shouldBe null
    }

    @Test
    fun `a pro user shares the selection`() {
        val vm = createVM(FakeUpgradeRepo(pro = true))
        vm.setSelection(setOf(entry.id))

        vm.onActionClick(HistoryActionBarItem.Share(listOf(entry)))

        vm.overlayState.value.proPromptOpen shouldBe false
        startedChooser()!!.action shouldBe Intent.ACTION_CHOOSER
    }

    @Test
    fun `a pro user reaches the delete confirmation`() {
        val vm = createVM(FakeUpgradeRepo(pro = true))
        vm.setSelection(setOf(entry.id))

        vm.onActionClick(HistoryActionBarItem.Delete(listOf(entry)))

        vm.overlayState.value.proPromptOpen shouldBe false
        vm.overlayState.value.deleteConfirmEntries shouldBe listOf(entry)
    }

    @Test
    fun `a pro user shares from the detail sheet`() {
        coEvery { historyRepo.getAttemptedPaths(entry.id) } returns attempted
        val vm = createVM(FakeUpgradeRepo(pro = true))

        vm.showEntryDetails(entry)
        vm.shareEntry(entry)

        vm.overlayState.value.proPromptOpen shouldBe false
        startedChooser()!!.action shouldBe Intent.ACTION_CHOOSER
    }

    @Test
    fun `dismissing the prompt closes it`() {
        val vm = createVM(FakeUpgradeRepo(pro = false))
        vm.setSelection(setOf(entry.id))
        vm.onActionClick(HistoryActionBarItem.Delete(listOf(entry)))
        vm.overlayState.value.proPromptOpen shouldBe true

        vm.dismissProPrompt()

        vm.overlayState.value.proPromptOpen shouldBe false
    }

    @Test
    fun `upgrading from the prompt closes it and navigates to the upgrade screen`() {
        val vm = createVM(FakeUpgradeRepo(pro = false))
        vm.setSelection(setOf(entry.id))
        vm.onActionClick(HistoryActionBarItem.Delete(listOf(entry)))

        vm.onProPromptUpgrade()

        vm.overlayState.value.proPromptOpen shouldBe false

        val events = mutableListOf<NavEvent>()
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.navEvents.toList(events) }
        try {
            events shouldBe listOf(NavEvent.GoTo(DestinationUpgrade))
        } finally {
            collector.cancel()
        }
    }

    /**
     * Both shipped repos turn a failed entitlement read into a settled Info carrying the error
     * rather than letting it escape the flow, so this - not an exception - is what a broken billing
     * lookup looks like to the gate, and it denies.
     */
    @Test
    fun `a settled state carrying a read error still denies`() {
        val vm = createVM(FakeUpgradeRepo(pro = false, error = IllegalStateException("billing is down")))
        vm.setSelection(setOf(entry.id))

        vm.onActionClick(HistoryActionBarItem.Delete(listOf(entry)))

        vm.overlayState.value.proPromptOpen shouldBe true
        vm.overlayState.value.deleteConfirmEntries shouldBe emptyList()
    }

    @Test
    fun `a selection cleared while the delete gate waits drops the delete`() = runTest {
        val upgradeRepo = FakeUpgradeRepo(pro = false, settled = false)
        val vm = createVM(upgradeRepo, TestDispatcherProvider(StandardTestDispatcher(testScheduler)))
        vm.setSelection(setOf(entry.id))

        vm.onActionClick(HistoryActionBarItem.Delete(listOf(entry)))
        // Billing has not settled, so the gate is parked while the action bar stays live.
        runCurrent()

        vm.clearSelection()
        upgradeRepo.settle(pro = true)
        advanceUntilIdle()

        vm.overlayState.value.deleteConfirmEntries shouldBe emptyList()
        vm.overlayState.value.proPromptOpen shouldBe false
    }

    @Test
    fun `a selection cleared while the share gate waits drops the share`() = runTest {
        val upgradeRepo = FakeUpgradeRepo(pro = false, settled = false)
        val vm = createVM(upgradeRepo, TestDispatcherProvider(StandardTestDispatcher(testScheduler)))
        vm.setSelection(setOf(entry.id))

        vm.onActionClick(HistoryActionBarItem.Share(listOf(entry)))
        runCurrent()

        vm.clearSelection()
        upgradeRepo.settle(pro = true)
        advanceUntilIdle()

        startedChooser() shouldBe null
        vm.overlayState.value.proPromptOpen shouldBe false
    }

    @Test
    fun `deselecting one of two entries while the share gate waits drops the share`() = runTest {
        val upgradeRepo = FakeUpgradeRepo(pro = false, settled = false)
        val vm = createVM(upgradeRepo, TestDispatcherProvider(StandardTestDispatcher(testScheduler)))
        vm.setSelection(setOf(entry.id, entry2.id))

        vm.onActionClick(HistoryActionBarItem.Share(listOf(entry, entry2)))
        // Billing has not settled, so the gate is parked while the action bar stays live.
        runCurrent()

        vm.toggleSelection(entry2.id)
        upgradeRepo.settle(pro = true)
        advanceUntilIdle()

        startedChooser() shouldBe null
    }

    @Test
    fun `deselecting one of two entries while the delete gate waits drops the delete`() = runTest {
        val upgradeRepo = FakeUpgradeRepo(pro = false, settled = false)
        val vm = createVM(upgradeRepo, TestDispatcherProvider(StandardTestDispatcher(testScheduler)))
        vm.setSelection(setOf(entry.id, entry2.id))

        vm.onActionClick(HistoryActionBarItem.Delete(listOf(entry, entry2)))
        runCurrent()

        vm.toggleSelection(entry2.id)
        upgradeRepo.settle(pro = true)
        advanceUntilIdle()

        vm.overlayState.value.deleteConfirmEntries shouldBe emptyList()
    }

    @Test
    fun `a share tapped before the delete coroutine is scheduled is dropped`() = runTest {
        val upgradeRepo = FakeUpgradeRepo(pro = false, settled = false)
        val vm = createVM(upgradeRepo, TestDispatcherProvider(StandardTestDispatcher(testScheduler)))
        vm.setSelection(setOf(entry.id))

        vm.onActionClick(HistoryActionBarItem.Delete(listOf(entry)))
        vm.onActionClick(HistoryActionBarItem.Share(listOf(entry)))

        upgradeRepo.settle(pro = true)
        advanceUntilIdle()

        vm.overlayState.value.deleteConfirmEntries shouldBe listOf(entry)
        startedChooser() shouldBe null
    }

    @Test
    fun `a sheet dismissed while the share gate waits raises no prompt`() = runTest {
        coEvery { historyRepo.getAttemptedPaths(entry.id) } returns attempted
        val upgradeRepo = FakeUpgradeRepo(pro = false, settled = false)
        val vm = createVM(upgradeRepo, TestDispatcherProvider(StandardTestDispatcher(testScheduler)))

        vm.showEntryDetails(entry)
        vm.shareEntry(entry)
        runCurrent()

        vm.showEntryDetails(null)
        upgradeRepo.settle(pro = false)
        advanceUntilIdle()

        vm.overlayState.value.proPromptOpen shouldBe false
        startedChooser() shouldBe null
    }
}
