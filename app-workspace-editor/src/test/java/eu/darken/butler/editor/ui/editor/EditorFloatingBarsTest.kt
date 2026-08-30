package eu.darken.butler.editor.ui.editor

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.ui.clipboard.ClipboardDisplayState
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.LocalWorkspaceBarCollapseStates
import eu.darken.butler.workspace.ui.floatingbar.WorkspaceBarCollapseStates
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import eu.darken.butler.editor.R as EditorR
import eu.darken.butler.workspace.R as WorkspaceR

/**
 * Guards the Editor's call site: the shared wrapper cannot tell whether this page passes the key,
 * the workspace type and the action mapping the Editor is supposed to pass.
 */
@Config(qualifiers = "w400dp-h800dp")
class EditorFloatingBarsTest : ComposeTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val workspaceId = Workspace.Id()
    private val barCollapseStates = WorkspaceBarCollapseStates()
    private val clipboardSource = MutableStateFlow(ClipboardDisplayState())
    private val pageActions = mutableListOf<EditorPageAction>()

    private val pasteLabel: String
        get() = context.getString(WorkspaceR.string.clipboard_paste)
    private val pasteAsFileLabel: String
        get() = context.getString(WorkspaceR.string.clipboard_text_paste_as_file)
    private val openInExplorerLabel: String
        get() = context.getString(WorkspaceR.string.clipboard_open_in_explorer)
    private val clipboardHeaderTitle: String
        get() = context.getString(WorkspaceR.string.clipboard_header_title)
    private val searchPlaceholder: String
        get() = context.getString(EditorR.string.editor_search_placeholder)

    private fun state(showSearchBar: Boolean = false) = EditorWorkspaceViewModel.State(
        id = workspaceId,
        title = caString("test.txt"),
        subTitle = caString("/storage/emulated/0/Documents/test.txt"),
        totalLines = 2,
        currentContent = "Line 1\nLine 2",
        showSearchBar = showSearchBar,
    )

    private fun clip() = ClipboardClip.Paths(
        origin = Workspace.Id(),
        mode = ClipboardClip.Paths.Mode.COPY,
        paths = listOf(
            LocalPathLookup(
                lookedUp = LocalPath.build(CLIP_PATH),
                fileType = FileType.FILE,
                size = null,
                modifiedAt = null,
            ),
        ),
    )

    private fun textClip() = ClipboardClip.Text(
        origin = Workspace.Id(),
        content = "Line 1",
    )

    private fun setPage(showSearchBar: Boolean = false) {
        val stateSource = MutableStateFlow(state(showSearchBar))
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(LocalWorkspaceBarCollapseStates provides barCollapseStates) {
                    EditorWorkspacePage(
                        workspaceId = workspaceId,
                        // Split-pane layout: keeps the mascot-bearing workspace button, which
                        // Robolectric cannot rasterise, out of the toolbar cutout.
                        design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                        mainStateSource = stateSource,
                        clipboardStateSource = clipboardSource,
                        onPageAction = { pageActions.add(it) },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    /**
     * The page composes its stacks itself, so the collapse registry it writes its per-bar fractions
     * into is the read-only route to the keys the bars registered under.
     */
    private fun bottomBarKeys(): Set<String> = barCollapseStates
        .snapshot()[workspaceId]
        ?.get(BarPosition.BOTTOM.persistedKey)
        ?.keys
        .orEmpty()

    @Test
    fun `the clipboard bar appears when a clip arrives after first composition`() {
        setPage()

        composeTestRule.onNodeWithContentDescription(pasteLabel).assertDoesNotExist()

        clipboardSource.value = ClipboardDisplayState(entries = listOf(clip()))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription(pasteLabel).assertIsDisplayed()
    }

    @Test
    fun `the clipboard bar registers under the key the editor persists`() {
        setPage()

        bottomBarKeys() shouldContain EditorBarKeys.CLIPBOARD
    }

    @Test
    fun `the paste affordance dispatches the editor's paste action`() {
        val clip = clip()
        setPage()
        clipboardSource.value = ClipboardDisplayState(entries = listOf(clip))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription(pasteLabel).performClick()

        // Filtered: the text editor dispatches navigation actions of its own while it lays out.
        pageActions.filterIsInstance<EditorPageAction.Clipboard>() shouldBe
            listOf(EditorPageAction.Clipboard.Paste(clip))
    }

    /**
     * A [ClipboardClip.Paths] clip renders the same paste affordance for every workspace type, so a
     * text clip is what separates the type this page passes from the Explorer's and the Searcher's.
     */
    @Test
    fun `a text clip keeps the editor's paste affordance`() {
        setPage()
        clipboardSource.value = ClipboardDisplayState(entries = listOf(textClip()))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription(pasteLabel).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(pasteAsFileLabel).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(openInExplorerLabel).assertDoesNotExist()
    }

    /**
     * For a BOTTOM stack the first-declared bar is furthest from the screen edge. Anchored on each
     * bar's outermost row, since the bars themselves carry no addressable node.
     */
    @Test
    fun `the bars stack in declaration order`() {
        setPage(showSearchBar = true)
        clipboardSource.value = ClipboardDisplayState(entries = listOf(clip()))
        composeTestRule.waitForIdle()

        val actionLabel = state(showSearchBar = true).availableActions
            .first { it.isVisible && !it.forceOverflow }
            .label.get(context)

        val searchRow = composeTestRule.onNodeWithText(searchPlaceholder).getUnclippedBoundsInRoot()
        val clipboardHeader = composeTestRule.onNodeWithText(clipboardHeaderTitle).getUnclippedBoundsInRoot()
        val clipboardRow = composeTestRule.onNodeWithText(CLIP_PATH).getUnclippedBoundsInRoot()
        val actionsBar = composeTestRule.onNodeWithContentDescription(actionLabel).getUnclippedBoundsInRoot()

        withClue("search bar sits above the clipboard bar") {
            (searchRow.bottom <= clipboardHeader.top) shouldBe true
        }
        withClue("clipboard bar sits above the actions bar") {
            (clipboardRow.bottom <= actionsBar.top) shouldBe true
        }
    }

    companion object {
        private const val CLIP_PATH = "/storage/emulated/0/Documents/report.pdf"
    }
}
