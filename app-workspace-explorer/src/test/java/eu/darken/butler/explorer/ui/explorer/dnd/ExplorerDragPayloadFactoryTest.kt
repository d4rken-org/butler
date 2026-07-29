package eu.darken.butler.explorer.ui.explorer.dnd

import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.archive.ArchivePathLookup
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel
import eu.darken.butler.explorer.ui.explorer.util.ExplorerSelectionState
import eu.darken.butler.workspace.contracts.dnd.WorkspaceDragPayload
import eu.darken.butler.workspace.contracts.explorer.PickerConfig
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ExplorerDragPayloadFactoryTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val directoryPath = LocalPath.build("/storage/emulated/0/DCIM")
    private val container = LocalPath.build("/storage/emulated/0/archive.zip")

    private fun file(name: String, mimeType: String = "image/jpeg") = ExplorerItem.RegularFile(
        lookup = LocalPathLookup(
            lookedUp = directoryPath.child(name),
            fileType = FileType.FILE,
            size = 1L,
            modifiedAt = null,
        ),
        mimeType = MimeInfo(mimeType),
    )

    private fun folder(name: String) = ExplorerItem.RegularDirectory(
        lookup = LocalPathLookup(
            lookedUp = directoryPath.child(name),
            fileType = FileType.DIRECTORY,
            size = null,
            modifiedAt = null,
        ),
    )

    private fun archiveEntry(name: String) = ExplorerItem.RegularFile(
        lookup = ArchivePathLookup(
            lookedUp = ArchivePath(container, listOf(name)),
            fileType = FileType.FILE,
            size = 1L,
            modifiedAt = null,
        ),
        mimeType = MimeInfo("text/plain"),
    )

    private fun state(
        selected: Set<ExplorerItem> = emptySet(),
        isWritable: Boolean = true,
        location: ExplorerLocation? = ExplorerLocation.Directory(
            info = ExplorerLocation.Directory.Info(isWritable = isWritable),
            path = directoryPath,
        ),
        pickerConfig: PickerConfig? = null,
    ) = ExplorerWorkspaceViewModel.State(
        currentLocation = location,
        selectionState = ExplorerSelectionState(selectedItems = selected),
        pickerConfig = pickerConfig,
    )

    private fun build(state: ExplorerWorkspaceViewModel.State, pressed: ExplorerItem) =
        ExplorerDragPayloadFactory.build(state, workspaceId, pressed)

    @Test
    fun `the pressed item joins the current selection`() {
        val selected = file("selected.jpg")
        val pressed = file("pressed.jpg")

        val payload = build(state(selected = setOf(selected)), pressed)!!

        payload.sourceWorkspaceId shouldBe workspaceId
        payload.items.map { it.path } shouldContainExactly listOf(selected.path, pressed.path)
    }

    @Test
    fun `a pressed item that is already selected is not duplicated`() {
        val pressed = file("pressed.jpg")

        val payload = build(state(selected = setOf(pressed)), pressed)!!

        payload.items.map { it.path } shouldContainExactly listOf(pressed.path)
    }

    @Test
    fun `a later selection change cannot alter an already built payload`() {
        val first = file("first.jpg")
        val snapshot = state(selected = setOf(first))
        val pressed = file("pressed.jpg")

        val payload = build(snapshot, pressed)!!
        // The page keeps handing out the state it collected; a newer state is a different value.
        build(state(selected = emptySet()), pressed)

        payload.items.map { it.path } shouldContainExactly listOf(first.path, pressed.path)
    }

    @Test
    fun `kinds cover directories, text files and everything else`() {
        val payload = build(
            state(selected = setOf(folder("raw"), file("notes.txt", "text/plain"))),
            file("photo.jpg"),
        )!!

        payload.items.map { it.kind } shouldContainExactly listOf(
            WorkspaceDragPayload.Kind.DIRECTORY,
            WorkspaceDragPayload.Kind.FILE_TEXT,
            WorkspaceDragPayload.Kind.FILE_OTHER,
        )
    }

    @Test
    fun `moving is allowed from a writable directory`() {
        build(state(), file("photo.jpg"))!!.allowMove shouldBe true
    }

    @Test
    fun `moving is blocked in a non-writable directory`() {
        build(state(isWritable = false), file("photo.jpg"))!!.allowMove shouldBe false
    }

    @Test
    fun `moving is blocked inside an archive`() {
        val archiveLocation = ExplorerLocation.Directory(
            info = ExplorerLocation.Directory.Info(isWritable = true),
            path = ArchivePath(container, listOf("sub")),
        )

        build(state(location = archiveLocation), archiveEntry("file.txt"))!!.allowMove shouldBe false
    }

    @Test
    fun `moving is blocked when an archive entry is part of the drag`() {
        val payload = build(state(selected = setOf(archiveEntry("file.txt"))), file("photo.jpg"))!!

        payload.allowMove shouldBe false
    }

    @Test
    fun `picker mode never drags`() {
        val pickerConfig = mockk<PickerConfig>()

        build(state(pickerConfig = pickerConfig), file("photo.jpg")) shouldBe null
    }

    @Test
    fun `non-lookup items never drag`() {
        build(state(), ExplorerItem.Peek(directoryPath.child("peeked.jpg"))) shouldBe null
    }

    @Test
    fun `a location that is not a directory never drags`() {
        build(state(location = ExplorerLocation.Home()), file("photo.jpg")) shouldBe null
        build(state(location = null), file("photo.jpg")) shouldBe null
    }
}
