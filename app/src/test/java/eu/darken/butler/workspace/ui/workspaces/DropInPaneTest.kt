package eu.darken.butler.workspace.ui.workspaces

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.workspace.contracts.dnd.WorkspaceDragPayload
import eu.darken.butler.workspace.core.OpenInNewTabsUseCase
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class DropInPaneTest : BaseTest() {

    private val path = LocalPath.build("/storage/emulated/0/Documents/notes.txt")

    private fun item(kind: WorkspaceDragPayload.Kind) = WorkspaceDragPayload.Item(path = path, kind = kind)

    @Test
    fun `directories open in the explorer`() {
        item(WorkspaceDragPayload.Kind.DIRECTORY).toOpenInNewTabsItem() shouldBe
            OpenInNewTabsUseCase.Item.Directory(path)
    }

    @Test
    fun `text files open in the editor`() {
        item(WorkspaceDragPayload.Kind.FILE_TEXT).toOpenInNewTabsItem() shouldBe
            OpenInNewTabsUseCase.Item.File(path, isText = true)
    }

    @Test
    fun `everything else opens in the viewer`() {
        item(WorkspaceDragPayload.Kind.FILE_OTHER).toOpenInNewTabsItem() shouldBe
            OpenInNewTabsUseCase.Item.File(path, isText = false)
    }

    @Test
    fun `a newly created workspace takes the target pane`() {
        val existing = Workspace.Id()
        val created = Workspace.Id()

        paneAssignmentAfterDrop(mapOf(0 to existing), paneIndex = 1, workspaceId = created) shouldBe
            mapOf(0 to existing, 1 to created)
    }

    @Test
    fun `an already open workspace leaves its previous pane`() {
        val other = Workspace.Id()
        val moved = Workspace.Id()

        paneAssignmentAfterDrop(
            current = mapOf(0 to moved, 1 to other),
            paneIndex = 2,
            workspaceId = moved,
        ) shouldBe mapOf(1 to other, 2 to moved)
    }

    @Test
    fun `dropping into the pane a workspace already occupies changes nothing`() {
        val open = Workspace.Id()

        paneAssignmentAfterDrop(mapOf(0 to open), paneIndex = 0, workspaceId = open) shouldBe mapOf(0 to open)
    }
}
