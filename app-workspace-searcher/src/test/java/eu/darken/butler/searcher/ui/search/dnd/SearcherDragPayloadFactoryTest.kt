package eu.darken.butler.searcher.ui.search.dnd

import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.archive.ArchivePathLookup
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.resultKey
import eu.darken.butler.searcher.ui.search.SearcherWorkspaceViewModel
import eu.darken.butler.searcher.ui.search.util.SearcherSelectionState
import eu.darken.butler.workspace.contracts.dnd.WorkspaceDragPayload
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class SearcherDragPayloadFactoryTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val container = LocalPath.build("/storage/emulated/0/archive.zip")

    private fun file(name: String) = SearchItem.RegularFile(
        lookup = LocalPathLookup(
            lookedUp = LocalPath.build("/storage/emulated/0/Documents", name),
            fileType = FileType.FILE,
            size = 1L,
            modifiedAt = null,
        ),
        matchedQuery = "",
    )

    private fun folder(name: String) = SearchItem.RegularDirectory(
        lookup = LocalPathLookup(
            lookedUp = LocalPath.build("/storage/emulated/0", name),
            fileType = FileType.DIRECTORY,
            size = null,
            modifiedAt = null,
        ),
        matchedQuery = "",
    )

    private fun archiveEntry(name: String) = SearchItem.RegularFile(
        lookup = ArchivePathLookup(
            lookedUp = ArchivePath(container, listOf(name)),
            fileType = FileType.FILE,
            size = 1L,
            modifiedAt = null,
        ),
        matchedQuery = "",
    )

    private fun state(selected: List<SearchItem> = emptyList()) = SearcherWorkspaceViewModel.State.Ready(
        selectionState = SearcherSelectionState(
            selectableResults = selected,
            selectedResultIds = selected.map { it.resultKey }.toSet(),
        ),
    )

    private fun build(state: SearcherWorkspaceViewModel.State, pressed: SearchItem) =
        SearcherDragPayloadFactory.build(state, workspaceId, pressed)

    @Test
    fun `the pressed result joins the current selection`() {
        val selected = file("selected.pdf")
        val pressed = file("pressed.pdf")

        val payload = build(state(listOf(selected)), pressed)!!

        payload.sourceWorkspaceId shouldBe workspaceId
        payload.items.map { it.path } shouldContainExactly listOf(selected.path, pressed.path)
    }

    @Test
    fun `a pressed result that is already selected is not duplicated`() {
        val pressed = file("pressed.pdf")

        val payload = build(state(listOf(pressed)), pressed)!!

        payload.items.map { it.path } shouldContainExactly listOf(pressed.path)
    }

    @Test
    fun `a later selection change cannot alter an already built payload`() {
        val first = file("first.pdf")
        val snapshot = state(listOf(first))
        val pressed = file("pressed.pdf")

        val payload = build(snapshot, pressed)!!
        build(state(), pressed)

        payload.items.map { it.path } shouldContainExactly listOf(first.path, pressed.path)
    }

    @Test
    fun `kinds cover directories, text files and everything else`() {
        val payload = build(state(listOf(folder("Pictures"), file("notes.txt"))), file("photo.jpg"))!!

        payload.items.map { it.kind } shouldContainExactly listOf(
            WorkspaceDragPayload.Kind.DIRECTORY,
            WorkspaceDragPayload.Kind.FILE_TEXT,
            WorkspaceDragPayload.Kind.FILE_OTHER,
        )
    }

    @Test
    fun `moving is allowed for regular results`() {
        build(state(), file("photo.jpg"))!!.allowMove shouldBe true
    }

    @Test
    fun `moving is blocked when an archive entry is part of the drag`() {
        build(state(), archiveEntry("file.txt"))!!.allowMove shouldBe false
        build(state(listOf(archiveEntry("file.txt"))), file("photo.jpg"))!!.allowMove shouldBe false
    }

    @Test
    fun `a workspace that is not ready never drags`() {
        build(SearcherWorkspaceViewModel.State.Initializing, file("photo.jpg")) shouldBe null
        build(SearcherWorkspaceViewModel.State.Error(Exception("nope")), file("photo.jpg")) shouldBe null
    }
}
