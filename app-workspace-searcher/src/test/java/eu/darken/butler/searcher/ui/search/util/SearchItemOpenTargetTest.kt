package eu.darken.butler.searcher.ui.search.util

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.workspace.contracts.editor.EditorArguments
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.contracts.viewer.ViewerArguments
import eu.darken.butler.workspace.core.OpenInNewTabsUseCase
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/** The Searcher "Open" action routes by content, exactly like the Explorer one. */
class SearchItemOpenTargetTest : BaseTest() {

    private val useCase = OpenInNewTabsUseCase()

    private fun item(path: String, fileType: FileType = FileType.FILE): SearchItem = SearchItem.fromLookup(
        lookup = LocalPathLookup(
            lookedUp = LocalPath.build(path),
            fileType = fileType,
            size = 1L,
            modifiedAt = null,
        ),
        matchedQuery = "q",
    )

    private fun request(item: SearchItem) = useCase.createRequest(
        item = item.toOpenInNewTabsItem(),
        createExplorerArguments = { ExplorerArguments.Default(startPath = it) },
        createEditorArguments = { EditorArguments.Default(filePath = it) },
        createViewerArguments = { ViewerArguments.Default(filePath = it) },
    )

    @Test
    fun `a text file opens in the editor`() {
        val target = item("/storage/emulated/0/Documents/notes.txt")

        val request = request(target)
        request.type shouldBe Workspace.Type.EDITOR
        request.arguments shouldBe EditorArguments.Default(filePath = target.path)
    }

    @Test
    fun `an image opens in the viewer`() {
        val target = item("/storage/emulated/0/DCIM/photo.jpg")

        val request = request(target)
        request.type shouldBe Workspace.Type.VIEWER
        request.arguments shouldBe ViewerArguments.Default(filePath = target.path)
    }

    @Test
    fun `an unknown binary opens in the viewer`() {
        val target = item("/storage/emulated/0/Download/blob.bin")

        val request = request(target)
        request.type shouldBe Workspace.Type.VIEWER
        request.arguments shouldBe ViewerArguments.Default(filePath = target.path)
    }

    @Test
    fun `a directory opens in the explorer`() {
        val target = item("/storage/emulated/0/DCIM", fileType = FileType.DIRECTORY)

        val request = request(target)
        request.type shouldBe Workspace.Type.EXPLORER
        request.arguments shouldBe ExplorerArguments.Default(startPath = target.path)
    }

    @Test
    fun `single open and multi select agree on the target workspace type`() {
        val targets = listOf(
            item("/storage/emulated/0/Documents/notes.txt"),
            item("/storage/emulated/0/DCIM/photo.jpg"),
            item("/storage/emulated/0/Download/blob.bin"),
            item("/storage/emulated/0/DCIM", fileType = FileType.DIRECTORY),
        )

        val analysis = useCase.analyze(
            OpenInNewTabsUseCase.Request(
                items = targets.map { it.toOpenInNewTabsItem() },
                sourceWorkspaceId = Workspace.Id(),
            ),
        )

        analysis.textFilesToOpen.size shouldBe 1
        analysis.viewerFilesToOpen.size shouldBe 2
        analysis.directoriesToOpen.size shouldBe 1

        targets.forEach { target ->
            request(target).type shouldBe useCase.classify(target.toOpenInNewTabsItem())
        }
    }
}
