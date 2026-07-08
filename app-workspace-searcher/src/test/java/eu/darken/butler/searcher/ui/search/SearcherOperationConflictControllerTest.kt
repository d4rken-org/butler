package eu.darken.butler.searcher.ui.search

import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.issue.Issue
import eu.darken.butler.searcher.core.SearcherWorkspace
import eu.darken.butler.workspace.core.operations.Operation
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class SearcherOperationConflictControllerTest : BaseTest() {

    private fun CoroutineScope.controller(
        pendingConflicts: Flow<Map<Operation.Id, Issue>> = MutableStateFlow(emptyMap()),
        workspace: SearcherWorkspace = mockk<SearcherWorkspace>().apply {
            every { resolveConflict(any(), any()) } just Runs
        },
    ) = SearcherOperationConflictController(
        pendingConflicts = pendingConflicts,
        workspace = { workspace },
        doLaunch = { block -> launch { block() } },
        tag = "test",
    )

    @Test
    fun `observer auto-surfaces the first pending conflict`() = runTest {
        val opId = Operation.Id()
        val issue = mockk<Issue>()
        val conflicts = MutableStateFlow<Map<Operation.Id, Issue>>(emptyMap())
        val controller = controller(pendingConflicts = conflicts)

        val collector = controller.conflictObserver.launchIn(this)
        runCurrent()
        controller.issueState.value shouldBe null

        conflicts.value = mapOf(opId to issue)
        runCurrent()
        controller.issueState.value shouldBe issue

        collector.cancel()
    }

    @Test
    fun `observer clears state when conflicts disappear`() = runTest {
        val opId = Operation.Id()
        val workspace = mockk<SearcherWorkspace>().apply {
            every { resolveConflict(any(), any()) } just Runs
        }
        val conflicts = MutableStateFlow<Map<Operation.Id, Issue>>(mapOf(opId to mockk<Issue>()))
        val controller = controller(pendingConflicts = conflicts, workspace = workspace)

        val collector = controller.conflictObserver.launchIn(this)
        runCurrent()
        controller.issueState.value shouldNotBe null

        conflicts.value = emptyMap()
        runCurrent()
        controller.issueState.value shouldBe null

        // The surfaced operation id must be cleared too - resolving now must not reach the workspace
        controller.resolve(mockk<PathActionIssue.Resolution>())
        runCurrent()
        verify(exactly = 0) { workspace.resolveConflict(any(), any()) }

        collector.cancel()
    }

    @Test
    fun `resolve forwards to the workspace with the surfaced operation id`() = runTest {
        val opId = Operation.Id()
        val workspace = mockk<SearcherWorkspace>().apply {
            every { resolveConflict(any(), any()) } just Runs
        }
        val resolution = mockk<PathActionIssue.Resolution>()
        val conflicts = MutableStateFlow(mapOf(opId to mockk<Issue>()))
        val controller = controller(pendingConflicts = conflicts, workspace = workspace)

        val collector = controller.conflictObserver.launchIn(this)
        runCurrent()

        controller.resolve(resolution)
        runCurrent()

        verify { workspace.resolveConflict(opId, resolution) }
        collector.cancel()
    }

    @Test
    fun `resolve without a surfaced conflict does not call the workspace`() = runTest {
        val workspace = mockk<SearcherWorkspace>().apply {
            every { resolveConflict(any(), any()) } just Runs
        }
        val controller = controller(workspace = workspace)

        controller.resolve(mockk<PathActionIssue.Resolution>())
        runCurrent()

        verify(exactly = 0) { workspace.resolveConflict(any(), any()) }
    }
}
