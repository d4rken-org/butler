package eu.darken.butler.explorer.ui.explorer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Lan
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import eu.darken.butler.workspace.contracts.viewer.ViewerArguments
import eu.darken.butler.workspace.core.OpenInNewTabsUseCase
import eu.darken.butler.workspace.core.OpenSelectionMode
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceRemote
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ExplorerOpenSelectionControllerTest : BaseTest() {

    private val workspaceId = Workspace.Id()

    private fun fileItem(name: String) = ExplorerItem.RegularFile(
        lookup = LocalPathLookup(
            lookedUp = LocalPath.build(BASE_PATH, name),
            fileType = FileType.FILE,
            size = 1L,
            modifiedAt = null,
        ),
        mimeType = MimeInfo("image/png"),
    )

    private fun directoryItem(name: String) = ExplorerItem.RegularDirectory(
        lookup = LocalPathLookup(
            lookedUp = LocalPath.build(BASE_PATH, name),
            fileType = FileType.DIRECTORY,
            size = 0L,
            modifiedAt = null,
        ),
    )

    private fun storageItem() = ExplorerItem.Storage.Local(
        localId = "primary",
        displayName = "Internal storage".toCaString(),
        displayIcon = Icons.TwoTone.Lan,
        target = ExplorerNavigation.Target.Directory(LocalPath.build(BASE_PATH, "storage")),
    )

    private fun dialogs() = ExplorerDialogController(
        filterState = { mockk() },
        useRegexPatterns = { false },
        clearSelection = {},
        tag = "test",
    )

    private val executed = mutableListOf<WorkspaceAction>()

    private fun workspaceRemote(): WorkspaceRemote = mockk<WorkspaceRemote>().apply {
        coEvery { execute(any()) } answers {
            val action = firstArg<WorkspaceAction>()
            executed.add(action)
            when (action) {
                is WorkspaceAction.CreateBatch -> WorkspaceAction.CreateBatch.Result.Cancelled
                else -> WorkspaceAction.Create.Result.Success(Workspace.Id())
            }
        }
        coEvery { emitEvent(any()) } just Runs
    }

    private var selection: Set<ExplorerItem> = emptySet()
    private var selectionCleared = 0

    private fun CoroutineScope.controller(
        dialogs: ExplorerDialogController = dialogs(),
        orderSelection: suspend (Collection<ExplorerItem>) -> List<ExplorerItem> = { it.toList() },
    ) = ExplorerOpenSelectionController(
        workspaceId = workspaceId,
        selectedItems = { selection },
        orderSelection = orderSelection,
        dialogs = dialogs,
        workspaceRemote = workspaceRemote(),
        openInNewTabsUseCase = OpenInNewTabsUseCase(),
        clearSelection = { selectionCleared++ },
        doLaunch = { block -> launch { block() } },
        tag = "test",
    )

    private fun ExplorerDialogController.openSelection() =
        current().shouldBeInstanceOf<ExplorerDialogState.OpenSelection>()

    @Test
    fun `the snapshot follows orderSelection, not the insertion order`() = runTest {
        val a = fileItem("a.png")
        val b = fileItem("b.png")
        val c = fileItem("c.png")
        selection = linkedSetOf(b, c, a)
        val dialogs = dialogs()
        val controller = controller(dialogs = dialogs, orderSelection = { items -> items.sortedBy { it.id } })

        controller.showChooser()

        val state = dialogs.openSelection()
        state.items shouldBe listOf(a, b, c)
        state.viewerModesAvailable shouldBe true
        state.viewerPaths shouldBe listOf(a.path, b.path, c.path)
    }

    @Test
    fun `a directory in the selection disables the viewer modes`() = runTest {
        selection = linkedSetOf(fileItem("a.png"), directoryItem("folder"))
        val dialogs = dialogs()

        controller(dialogs = dialogs).showChooser()

        val state = dialogs.openSelection()
        state.viewerModesAvailable shouldBe false
        state.viewerPaths shouldBe emptyList()
        state.items.size shouldBe 2
    }

    @Test
    fun `a storage in the selection disables the viewer modes`() = runTest {
        selection = linkedSetOf(storageItem())
        val dialogs = dialogs()

        controller(dialogs = dialogs).showChooser()

        val state = dialogs.openSelection()
        state.viewerModesAvailable shouldBe false
        state.viewerPaths shouldBe emptyList()
    }

    @Test
    fun `an empty selection shows no chooser`() = runTest {
        selection = emptySet()
        val dialogs = dialogs()
        var ordered = false

        controller(dialogs = dialogs, orderSelection = { ordered = true; it.toList() }).showChooser()

        dialogs.current() shouldBe ExplorerDialogState.None
        ordered shouldBe false
    }

    @Test
    fun `a later selection change does not alter the snapshot`() = runTest {
        val a = fileItem("a.png")
        val b = fileItem("b.png")
        selection = linkedSetOf(a, b)
        val dialogs = dialogs()
        val controller = controller(dialogs = dialogs)

        controller.showChooser()
        selection = linkedSetOf(fileItem("other.png"))

        val state = dialogs.openSelection()
        state.items shouldBe listOf(a, b)

        controller.onModeSelected(state, OpenSelectionMode.VIEW_IN_TAB)
        runCurrent()

        val arguments = executed.single().shouldBeInstanceOf<WorkspaceAction.Create>().arguments
            .shouldBeInstanceOf<ViewerArguments.Default>()
        arguments.stepPaths shouldBe listOf(a.path, b.path)
    }

    @Test
    fun `view in tab opens one viewer tab that skips content dedup`() = runTest {
        val a = fileItem("a.png")
        val b = fileItem("b.png")
        selection = linkedSetOf(b, a)
        val dialogs = dialogs()
        val controller = controller(dialogs = dialogs, orderSelection = { items -> items.sortedBy { it.id } })

        controller.showChooser()
        controller.onModeSelected(dialogs.openSelection(), OpenSelectionMode.VIEW_IN_TAB)
        runCurrent()

        val create = executed.single().shouldBeInstanceOf<WorkspaceAction.Create>()
        create.type shouldBe Workspace.Type.VIEWER
        create.skipContentDedup shouldBe true
        create.sourceWorkspaceId shouldBe workspaceId
        val arguments = create.arguments.shouldBeInstanceOf<ViewerArguments.Default>()
        arguments.filePath shouldBe a.path
        arguments.stepPaths shouldBe listOf(a.path, b.path)
        arguments.callerWorkspaceId shouldBe null
        selectionCleared shouldBe 1
        dialogs.current() shouldBe ExplorerDialogState.None
    }

    @Test
    fun `view here opens the viewer as an overlay of this explorer`() = runTest {
        val a = fileItem("a.png")
        val b = fileItem("b.png")
        selection = linkedSetOf(a, b)
        val dialogs = dialogs()
        val controller = controller(dialogs = dialogs)

        controller.showChooser()
        controller.onModeSelected(dialogs.openSelection(), OpenSelectionMode.VIEW_HERE)
        runCurrent()

        val create = executed.single().shouldBeInstanceOf<WorkspaceAction.Create>()
        val arguments = create.arguments.shouldBeInstanceOf<ViewerArguments.Default>()
        arguments.callerWorkspaceId shouldBe workspaceId
        arguments.filePath shouldBe a.path
        arguments.stepPaths shouldBe listOf(a.path, b.path)
        selectionCleared shouldBe 1
    }

    @Test
    fun `each in tab opens a viewer per file and an explorer per folder`() = runTest {
        val file = fileItem("a.png")
        val folder = directoryItem("folder")
        selection = linkedSetOf(file, folder)
        val dialogs = dialogs()
        val controller = controller(dialogs = dialogs)

        controller.showChooser()
        controller.onModeSelected(dialogs.openSelection(), OpenSelectionMode.EACH_IN_TAB)
        runCurrent()

        val batch = executed.single().shouldBeInstanceOf<WorkspaceAction.CreateBatch>()
        batch.sourceWorkspaceId shouldBe workspaceId
        batch.requests.map { it.type }.sorted() shouldBe listOf(Workspace.Type.EXPLORER, Workspace.Type.VIEWER).sorted()
        batch.requests.single { it.type == Workspace.Type.VIEWER }.arguments
            .shouldBeInstanceOf<ViewerArguments.Default>().filePath shouldBe file.path
        selectionCleared shouldBe 1
    }

    @Test
    fun `a second tap on the chooser issues nothing`() = runTest {
        selection = linkedSetOf(fileItem("a.png"))
        val dialogs = dialogs()
        val controller = controller(dialogs = dialogs)

        controller.showChooser()
        val state = dialogs.openSelection()
        controller.onModeSelected(state, OpenSelectionMode.VIEW_IN_TAB)
        controller.onModeSelected(state, OpenSelectionMode.EACH_IN_TAB)
        runCurrent()

        executed.single().shouldBeInstanceOf<WorkspaceAction.Create>()
        selectionCleared shouldBe 1
    }

    @Test
    fun `dismissing the chooser keeps the selection`() = runTest {
        selection = linkedSetOf(fileItem("a.png"))
        val dialogs = dialogs()
        val controller = controller(dialogs = dialogs)

        controller.showChooser()
        val state = dialogs.openSelection()
        dialogs.dismiss()
        controller.onModeSelected(state, OpenSelectionMode.VIEW_HERE)
        runCurrent()

        executed shouldBe emptyList()
        selectionCleared shouldBe 0
    }

    @Test
    fun `a selected file the filter hides keeps its place in the sorted order`() {
        val a = fileItem("a.png")
        val hidden = fileItem(".hidden.png")
        val b = fileItem("b.png")

        val ordered = orderSelectionForDisplay(
            selected = linkedSetOf(b, hidden, a),
            rawItems = listOf(a, hidden, b),
            displayed = listOf(a, b),
            sortForDisplay = { items -> items.sortedBy { it.id } },
        )

        ordered shouldBe listOf(hidden, a, b)
    }

    @Test
    fun `the sort gets the selection in listing order, for locations that keep it`() {
        val a = fileItem("a.png")
        val b = fileItem("b.png")
        val c = fileItem("c.png")

        val ordered = orderSelectionForDisplay(
            selected = linkedSetOf(c, a),
            rawItems = listOf(c, b, a),
            displayed = listOf(c, b, a),
            sortForDisplay = { it },
        )

        ordered shouldBe listOf(c, a)
    }

    @Test
    fun `without a resolved sort the displayed order is used, unlisted items last`() {
        val a = fileItem("a.png")
        val b = fileItem("b.png")
        val hidden1 = fileItem(".x.png")
        val hidden2 = fileItem(".y.png")

        val ordered = orderSelectionForDisplay(
            selected = linkedSetOf(hidden2, a, hidden1, b),
            rawItems = listOf(a, b, hidden1, hidden2),
            displayed = listOf(b, a),
            sortForDisplay = null,
        )

        ordered shouldBe listOf(b, a, hidden2, hidden1)
    }

    companion object {
        private const val BASE_PATH = "/tmp/open-selection-test"
    }
}
