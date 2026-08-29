package eu.darken.butler.searcher.core

import eu.darken.butler.common.error.ErrorIncident
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.searcher.core.engine.SearchEngine
import eu.darken.butler.searcher.core.operations.DeleteOperation
import eu.darken.butler.searcher.core.operations.SearcherCommand
import eu.darken.butler.workspace.contracts.searcher.FilenameQuery
import eu.darken.butler.workspace.contracts.searcher.SearcherArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.preview.FolderPreviewResolver
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.error.recordingIncidentStore

/**
 * The search runs in the workspace, which outlives the page: a failure has to be frozen where the
 * workspace publishes it, or a search that fails after the page is gone would only be frozen when
 * the user comes back - with a log trail and a timestamp from re-open time.
 */
class SearcherWorkspaceErrorIncidentTest : BaseTest() {

    private val incidentStore = recordingIncidentStore()

    private fun createWorkspace(failure: Exception): SearcherWorkspace {
        val engine = mockk<SearchEngine> {
            every { targetState } returns MutableStateFlow(emptyList())
            every { setupRequirements } returns MutableStateFlow(PathRequirements())
            every { accessErrorRequirements } returns MutableStateFlow(PathRequirements())
            every { clearTargetProgress() } just Runs
            every { targetProgressState } returns MutableStateFlow(emptyList())
            coEvery { search(any(), any()) } returns SearchEngine.Result.Error(failure)
        }
        return SearcherWorkspace(
            id = Workspace.Id(),
            creationArguments = SearcherArguments.Default(),
            dispatcherProvider = TestDispatcherProvider(),
            issueHandler = mockk<IssueHandler>(),
            operationsManager = mockk<OperationsManager> {
                every { operations } returns MutableStateFlow(emptyList<ManagedOperation>())
            },
            deleteOperationFactory = mockk<DeleteOperation.Factory>(),
            searchEngineFactory = mockk<SearchEngine.Factory> {
                every { create(any(), any()) } returns engine
            },
            fileSystemHinter = FileSystemHinter(),
            folderPreviewResolver = mockk<FolderPreviewResolver>(relaxUnitFun = true),
            errorIncidentStore = incidentStore,
        )
    }

    private fun searchCommand() = SearcherCommand.Search(
        filenameQuery = FilenameQuery(pattern = "test"),
        targets = emptyList(),
    )

    private fun awaitErrorState(workspace: SearcherWorkspace): SearcherWorkspace.State = runBlocking {
        withTimeout(10_000) {
            workspace.state.first { it.searchStatus == SearcherWorkspace.State.SearchStatus.ERROR }
        }
    }

    private fun awaitIncident(error: Throwable): ErrorIncident = runBlocking {
        withTimeout(10_000) {
            while (incidentStore.get(error) == null) delay(10)
            incidentStore.get(error)!!
        }
    }

    @Test
    fun `a failed search is frozen where the workspace publishes it`() {
        val boom = IllegalStateException("search blew up")
        val workspace = createWorkspace(boom)

        workspace.execute(searchCommand())

        (awaitErrorState(workspace).error === boom) shouldBe true
        val incident = awaitIncident(boom)
        (incident.error === boom) shouldBe true
        incident.occurredAtIsApproximate shouldBe false
        incident.context.containsKey("incident.frozenAtShare") shouldBe false
    }

    @Test
    fun `the share action hands over the incident the failure was frozen into`() {
        val boom = IllegalStateException("search blew up")
        val workspace = createWorkspace(boom)

        workspace.execute(searchCommand())
        val frozen = awaitIncident(boom)

        // What the page's share action does with the state it renders the error from
        val shared = runBlocking { incidentStore.getOrFreeze(awaitErrorState(workspace).error!!) }

        shared.incidentId shouldBe frozen.incidentId
        shared.occurredAtIsApproximate shouldBe false
        shared.context.containsKey("incident.frozenAtShare") shouldBe false
    }
}
