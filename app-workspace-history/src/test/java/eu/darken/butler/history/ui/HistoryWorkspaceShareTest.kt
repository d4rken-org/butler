package eu.darken.butler.history.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.history.HistoryEntry
import eu.darken.butler.workspace.core.operations.history.HistoryOutcome
import eu.darken.butler.workspace.core.operations.history.HistorySettings
import eu.darken.butler.workspace.core.operations.history.OperationHistoryRepo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
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
}
