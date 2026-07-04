package eu.darken.butler.workspace.ui.manager

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.defaultArguments
import eu.darken.butler.workspace.ui.WorkspacePageManager
import eu.darken.butler.workspace.ui.template.QuickCreateItem
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

class WorkspaceButtonViewModelTest : BaseTest() {

    private val workspaceRemote = mockk<WorkspaceRemote>(relaxed = true)
    private val pageManager = mockk<WorkspacePageManager>(relaxed = true)

    private fun createVM() = WorkspaceButtonViewModel(
        dispatchers = TestDispatcherProvider(),
        workspaceRemote = workspaceRemote,
        workspacePageManager = pageManager,
        workspaceTemplates = emptySet(),
    )

    private fun explorerItem() = QuickCreateItem(
        type = Workspace.Type.EXPLORER,
        icon = Icons.TwoTone.Add,
        title = "Explorer".toCaString(),
        arguments = Workspace.Type.EXPLORER.defaultArguments!!,
    )

    @Test
    fun `createWorkspace executes Create with the item type and args and requests selection`() {
        val newId = Workspace.Id()
        val action = slot<WorkspaceAction>()
        coEvery { workspaceRemote.execute(capture(action)) } returns WorkspaceAction.Create.Result.Success(newId)
        val item = explorerItem()

        createVM().createWorkspace(item)

        val created = action.captured as WorkspaceAction.Create
        created.type shouldBe Workspace.Type.EXPLORER
        created.arguments shouldBe item.arguments
        coVerify { workspaceRemote.emitEvent(WorkspaceEvent.SelectionRequested(newId)) }
    }

    @Test
    fun `createWorkspace on AlreadyOpen requests selection of the existing workspace`() {
        val existingId = Workspace.Id()
        coEvery { workspaceRemote.execute(any()) } returns WorkspaceAction.Create.Result.AlreadyOpen(existingId)

        createVM().createWorkspace(explorerItem())

        coVerify { workspaceRemote.emitEvent(WorkspaceEvent.SelectionRequested(existingId)) }
    }

    @Test
    fun `createWorkspace on LimitReached emits no selection event`() {
        coEvery { workspaceRemote.execute(any()) } returns WorkspaceAction.Create.Result.LimitReached

        createVM().createWorkspace(explorerItem())

        coVerify(exactly = 0) { workspaceRemote.emitEvent(any()) }
    }

    @Test
    fun `createTemplatesWorkspace executes Create for the templates picker`() {
        val newId = Workspace.Id()
        val action = slot<WorkspaceAction>()
        coEvery { workspaceRemote.execute(capture(action)) } returns WorkspaceAction.Create.Result.Success(newId)

        createVM().createTemplatesWorkspace()

        (action.captured as WorkspaceAction.Create).type shouldBe Workspace.Type.TEMPLATES
        coVerify { workspaceRemote.emitEvent(WorkspaceEvent.SelectionRequested(newId)) }
    }
}
