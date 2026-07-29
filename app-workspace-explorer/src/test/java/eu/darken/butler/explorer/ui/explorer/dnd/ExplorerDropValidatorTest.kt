package eu.darken.butler.explorer.ui.explorer.dnd

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel
import eu.darken.butler.workspace.contracts.dnd.WorkspaceDragPayload
import eu.darken.butler.workspace.contracts.explorer.PickerConfig
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ExplorerDropValidatorTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val sourceWorkspaceId = Workspace.Id()
    private val destination = LocalPath.build("/storage/emulated/0/Download")
    private val container = LocalPath.build("/storage/emulated/0/archive.zip")

    private fun payload(
        sourceWorkspaceId: Workspace.Id = this.sourceWorkspaceId,
        path: APath<*> = LocalPath.build("/storage/emulated/0/DCIM/photo.jpg"),
        kind: WorkspaceDragPayload.Kind = WorkspaceDragPayload.Kind.FILE_OTHER,
    ) = WorkspaceDragPayload(
        sourceWorkspaceId = sourceWorkspaceId,
        items = listOf(WorkspaceDragPayload.Item(path = path, kind = kind)),
        allowMove = true,
    )

    private fun state(
        location: ExplorerLocation? = ExplorerLocation.Directory(
            info = ExplorerLocation.Directory.Info(isWritable = true),
            path = destination,
        ),
        pickerConfig: PickerConfig? = null,
    ) = ExplorerWorkspaceViewModel.State(
        currentLocation = location,
        pickerConfig = pickerConfig,
    )

    @Test
    fun `a writable directory takes a drop from another workspace`() {
        validateDropDestination(state(), workspaceId, payload()) shouldBe destination
    }

    @Test
    fun `a drop from the same workspace is refused`() {
        validateDropDestination(state(), workspaceId, payload(sourceWorkspaceId = workspaceId)) shouldBe null
    }

    @Test
    fun `picker mode refuses drops`() {
        validateDropDestination(state(pickerConfig = mockk<PickerConfig>()), workspaceId, payload()) shouldBe null
    }

    @Test
    fun `a non-writable directory refuses drops`() {
        val location = ExplorerLocation.Directory(
            info = ExplorerLocation.Directory.Info(isWritable = false),
            path = destination,
        )

        validateDropDestination(state(location = location), workspaceId, payload()) shouldBe null
    }

    @Test
    fun `an archive refuses drops`() {
        val location = ExplorerLocation.Directory(
            info = ExplorerLocation.Directory.Info(isWritable = true),
            path = ArchivePath(container, listOf("sub")),
        )

        validateDropDestination(state(location = location), workspaceId, payload()) shouldBe null
    }

    @Test
    fun `locations without a directory refuse drops`() {
        validateDropDestination(state(location = ExplorerLocation.Home()), workspaceId, payload()) shouldBe null
        validateDropDestination(state(location = null), workspaceId, payload()) shouldBe null
    }

    @Test
    fun `path conflicts are refused`() {
        val fromSameFolder = payload(path = destination.child("photo.jpg"))

        validateDropDestination(state(), workspaceId, fromSameFolder) shouldBe null
    }

}
