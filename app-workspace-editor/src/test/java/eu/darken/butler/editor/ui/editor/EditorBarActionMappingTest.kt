package eu.darken.butler.editor.ui.editor

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.ui.clipboard.bar.ClipboardBarAction
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * Exhaustiveness proves every variant is handled, not that the four same-shaped branches point at
 * the matching page action.
 */
class EditorBarActionMappingTest : BaseTest() {

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
    fun `every clipboard bar action maps to its page action`() {
        val cases = listOf<Pair<ClipboardBarAction, EditorPageAction>>(
            ClipboardBarAction.Paste(clip) to EditorPageAction.Clipboard.Paste(clip),
            ClipboardBarAction.Remove(clip) to EditorPageAction.Clipboard.Remove(clip),
            ClipboardBarAction.ShowInfo(clip) to EditorPageAction.Clipboard.ShowInfo(clip),
            ClipboardBarAction.ClearAll to EditorPageAction.Clipboard.Clear,
        )

        cases.forEach { (action, expected) ->
            withClue(action.toString()) { action.toPageAction() shouldBe expected }
        }
    }
}
