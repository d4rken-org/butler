package eu.darken.butler.history.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.history.core.HistoryWorkspace
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.history.HistoryEntry
import eu.darken.butler.workspace.core.operations.history.HistoryFilter
import eu.darken.butler.workspace.core.operations.history.HistoryOutcome
import eu.darken.butler.workspace.core.operations.history.HistorySettings
import eu.darken.butler.workspace.core.operations.history.OperationHistoryRepo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
 * The detail sheet loads the attempted paths asynchronously; sharing must not race that load.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryWorkspaceShareTest : BaseTest() {

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

    /** An entry that reported no changes at all, so the sheet falls back to the attempted paths. */
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

    /** A second visible row, so a selection on it survives the state's prune-on-read. */
    private val otherEntry = entry.copy(id = "some-id", title = "Copy 1 item")

    private val historyWorkspace = mockk<HistoryWorkspace>().apply {
        every { filter } returns flowOf(HistoryFilter())
    }

    /** Settled and Pro, so the share gate resolves and these cases keep testing the share itself. */
    private val upgradeRepo = object : UpgradeRepo {
        override val storeSite = ""
        override val upgradeSite = ""
        override val betaSite = ""
        override val upgradeInfo = MutableStateFlow<UpgradeRepo.Info>(
            object : UpgradeRepo.Info {
                override val type = UpgradeRepo.Type.FOSS
                override val isPro = true
                override val isSettled = true
                override val upgradedAt: Instant? = null
                override val error: Throwable? = null
            }
        )

        override suspend fun refresh() = Unit
    }

    private val attempted = OperationHistoryRepo.AttemptedPaths(
        paths = listOf("/sdcard/ButlerQA", "/sdcard/ButlerQA/notes.txt"),
        totalCount = 2,
    )

    private fun createVM() = HistoryWorkspaceViewModel(
        id = Workspace.Id(),
        context = context,
        dispatchers = TestDispatcherProvider(),
        workspaceProvider = workspaceProvider,
        historyRepo = historyRepo,
        historySettings = historySettings,
        upgradeRepo = upgradeRepo,
    )

    /**
     * `startActivity` hands Robolectric the chooser; the intent the ViewModel actually built is the
     * `EXTRA_INTENT` payload inside it.
     */
    private fun lastSharedText(): String? {
        val chooser = shadowOf(application).nextStartedActivity ?: return null
        chooser.action shouldBe Intent.ACTION_CHOOSER
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        return send.getStringExtra(Intent.EXTRA_TEXT)
    }

    /** The shadow pops one intent per read, so draining it is how many launches are counted. */
    private fun startedChooserCount(): Int {
        var count = 0
        while (shadowOf(application).nextStartedActivity != null) count++
        return count
    }

    @Test
    fun `sharing before the attempted paths arrive still includes them`() {
        val gate = CompletableDeferred<OperationHistoryRepo.AttemptedPaths>()
        coEvery { historyRepo.getAttemptedPaths(entry.id) } coAnswers { gate.await() }

        val vm = createVM()

        // The sheet opens and the load is still in flight
        vm.showEntryDetails(entry)
        vm.overlayState.value.detailEntry shouldBe entry
        vm.overlayState.value.attemptedPaths shouldBe emptyList()

        vm.shareEntry(entry)

        // The query does return; this is about the ordering, not about a load that never finishes.
        gate.complete(attempted)

        val text = lastSharedText()!!
        text shouldContain "## Delete 2 items"
        text shouldContain "- `/sdcard/ButlerQA/notes.txt`"
        text shouldContain "- `/sdcard/ButlerQA`"

        vm.overlayState.value.attemptedPaths shouldBe attempted.paths
    }

    @Test
    fun `sharing after the attempted paths arrive includes them`() {
        coEvery { historyRepo.getAttemptedPaths(entry.id) } returns attempted

        val vm = createVM()
        vm.showEntryDetails(entry)
        vm.overlayState.value.attemptedPaths shouldBe attempted.paths

        vm.shareEntry(entry)

        val text = lastSharedText()!!
        text shouldContain "- `/sdcard/ButlerQA/notes.txt`"
    }

    @Test
    fun `tapping share twice while the paths load starts only one chooser`() {
        val gate = CompletableDeferred<OperationHistoryRepo.AttemptedPaths>()
        coEvery { historyRepo.getAttemptedPaths(entry.id) } coAnswers { gate.await() }

        val vm = createVM()
        vm.showEntryDetails(entry)

        // The button stays enabled while the query is in flight, so a second tap is reachable.
        vm.shareEntry(entry)
        vm.shareEntry(entry)

        gate.complete(attempted)

        startedChooserCount() shouldBe 1
    }

    @Test
    fun `a selection made while the share waits survives it`() {
        val gate = CompletableDeferred<OperationHistoryRepo.AttemptedPaths>()
        coEvery { historyRepo.getAttemptedPaths(entry.id) } coAnswers { gate.await() }
        every { workspaceProvider.retrieve(any()) } returns flowOf(historyWorkspace)
        every { historySettings.maxHistoryItems.flow } returns flowOf(200)
        every { historyRepo.query(any(), any()) } returns flowOf(listOf(entry, otherEntry))
        every { historyRepo.observeCount() } returns flowOf(2)

        val vm = createVM()
        val states = mutableListOf<HistoryWorkspaceViewModel.State?>()
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.toList(states) }

        try {
            vm.showEntryDetails(entry)
            vm.shareEntry(entry)

            // Selection mode is still usable behind the sheet while the query is in flight.
            vm.toggleSelection(otherEntry.id)
            states.last()!!.selectedIds shouldBe setOf(otherEntry.id)

            gate.complete(attempted)

            states.last()!!.selectedIds shouldBe setOf(otherEntry.id)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun `dismissing the sheet before the paths arrive drops the share`() {
        val gate = CompletableDeferred<OperationHistoryRepo.AttemptedPaths>()
        coEvery { historyRepo.getAttemptedPaths(entry.id) } coAnswers { gate.await() }

        val vm = createVM()
        vm.showEntryDetails(entry)
        vm.shareEntry(entry)

        vm.showEntryDetails(null)
        gate.complete(attempted)

        startedChooserCount() shouldBe 0
    }
}
