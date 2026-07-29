package eu.darken.butler.templates.ui

import eu.darken.butler.workspace.contracts.developer.DeveloperArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceRemote
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

/**
 * Picking a template whose singleton is already open focuses the existing tab - that selection must
 * carry the picker's own workspace as the placement hint, so the tab surfaces next to it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TemplatesWorkspaceViewModelCreateTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val existingId = Workspace.Id()
    private val emitted = mutableListOf<WorkspaceEvent>()

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        emitted.clear()
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun makeViewModel(createResult: WorkspaceAction.Create.Result): TemplatesWorkspaceViewModel {
        val remote = mockk<WorkspaceRemote> {
            every { state } returns emptyFlow()
            every { events } returns emptyFlow()
            coEvery { emitEvent(any()) } coAnswers { emitted += firstArg<WorkspaceEvent>() }
            coEvery { execute(any()) } returns createResult
        }
        return TemplatesWorkspaceViewModel(
            id = workspaceId,
            dispatchers = TestDispatcherProvider(),
            workspaceRemote = remote,
            upgradeRepo = mockk(relaxed = true),
            workspaceTemplates = emptySet(),
        )
    }

    @Test
    fun `focusing an already open singleton carries the picker as source`() {
        val vm = makeViewModel(WorkspaceAction.Create.Result.AlreadyOpen(existingId))

        vm.createWorkspace(
            WorkspaceAction.Create(
                type = Workspace.Type.DEVELOPER,
                arguments = DeveloperArguments.Default(),
                replace = workspaceId,
                autoFocus = true,
            )
        )

        val selection = emitted.single().shouldBeInstanceOf<WorkspaceEvent.SelectionRequested>()
        selection.workspaceId shouldBe existingId
        selection.sourceWorkspaceId shouldBe workspaceId
    }

    @Test
    fun `a fresh create emits no selection event`() {
        val vm = makeViewModel(WorkspaceAction.Create.Result.Success(Workspace.Id()))

        vm.createWorkspace(
            WorkspaceAction.Create(
                type = Workspace.Type.DEVELOPER,
                arguments = DeveloperArguments.Default(),
                replace = workspaceId,
                autoFocus = true,
            )
        )

        emitted.shouldBeEmpty()
    }
}
