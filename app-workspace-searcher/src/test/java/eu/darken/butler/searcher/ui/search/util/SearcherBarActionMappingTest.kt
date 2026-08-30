package eu.darken.butler.searcher.ui.search.util

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.clipboard.bar.ClipboardBarAction
import eu.darken.butler.workspace.ui.operations.bar.OperationsBarAction
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * `Operations.Cancel` and `Overlays.RequestCancelOperation` both exist and both compile here, and
 * the Searcher answers a paste by opening the target in the Explorer. Neither is guessable.
 */
class SearcherBarActionMappingTest : BaseTest() {

    private val id = Operation.Id()

    private val clip = ClipboardClip.Paths(
        origin = Workspace.Id(),
        mode = ClipboardClip.Paths.Mode.COPY,
        paths = listOf(
            LocalPathLookup(
                lookedUp = LocalPath.build("/storage/emulated/0/Documents/report.pdf"),
                fileType = FileType.FILE,
                size = null,
                modifiedAt = null,
            ),
        ),
    )

    @Test
    fun `every operations bar action maps to its page action`() {
        val cases = listOf<Pair<OperationsBarAction, SearcherPageAction>>(
            OperationsBarAction.RequestCancel(id) to SearcherPageAction.Overlays.RequestCancelOperation(id),
            OperationsBarAction.Dismiss(id) to SearcherPageAction.Operations.Dismiss(id),
            OperationsBarAction.ShowConflict(id) to SearcherPageAction.Operations.ShowConflict(id),
            OperationsBarAction.ShowDetails(id) to SearcherPageAction.Overlays.ShowOperationDetails(id),
            OperationsBarAction.ClearCompleted to SearcherPageAction.Operations.ClearCompleted,
        )

        cases.forEach { (action, expected) ->
            withClue(action.toString()) { action.toPageAction() shouldBe expected }
        }
    }

    @Test
    fun `every clipboard bar action maps to its page action`() {
        val cases = listOf<Pair<ClipboardBarAction, SearcherPageAction>>(
            ClipboardBarAction.Paste(clip) to SearcherPageAction.Clipboard.OpenInExplorer(clip),
            ClipboardBarAction.Remove(clip) to SearcherPageAction.Clipboard.RemoveEntry(clip),
            ClipboardBarAction.ShowInfo(clip) to SearcherPageAction.Clipboard.ClickEntry(clip),
            ClipboardBarAction.ClearAll to SearcherPageAction.Clipboard.ClearAll,
        )

        cases.forEach { (action, expected) ->
            withClue(action.toString()) { action.toPageAction() shouldBe expected }
        }
    }
}
