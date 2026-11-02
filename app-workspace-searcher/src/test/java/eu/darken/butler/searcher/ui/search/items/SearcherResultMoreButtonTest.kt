package eu.darken.butler.searcher.ui.search.items

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.searcher.core.SearcherViewStyle
import eu.darken.butler.searcher.core.resultKey
import eu.darken.butler.searcher.ui.search.SearcherWorkspaceViewModel
import eu.darken.butler.searcher.ui.search.preview.SearcherMockDataProvider
import eu.darken.butler.searcher.ui.search.util.SearcherSelectionState
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest
import eu.darken.butler.workspace.R as WorkspaceR

/** With "open files on tap" a file result carries a ⋮ that opens its quick actions instead. */
class SearcherResultMoreButtonTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val moreLabel get() = context.getString(WorkspaceR.string.workspace_action_more)

    private val file = SearcherMockDataProvider.createMockTextFile(name = "report.txt")
    private val directory = SearcherMockDataProvider.createMockDirectory(name = "Documents")

    private var clicks = 0
    private var moreClicks = 0

    private fun render(content: @Composable () -> Unit) {
        composeTestRule.setContent { PreviewWrapper { content() } }
    }

    private fun moreButton() = composeTestRule.onNodeWithContentDescription(moreLabel)

    @Composable
    private fun FileRow(onMoreClick: (() -> Unit)?) = SelectableFileRow(
        result = file,
        density = SearcherViewStyle.Density.COMFORTABLE,
        isSelected = false,
        isSelectionMode = false,
        onClick = { clicks++ },
        onLongPress = {},
        onMoreClick = onMoreClick,
    )

    @Composable
    private fun FileTile(onMoreClick: (() -> Unit)?) = Box(Modifier.size(200.dp)) {
        SelectableFileGrid(
            result = file,
            density = SearcherViewStyle.Density.COMFORTABLE,
            isSelected = false,
            isSelectionMode = false,
            onClick = { clicks++ },
            onLongPress = {},
            onMoreClick = onMoreClick,
        )
    }

    @Test
    fun `row with a more callback shows the more button`() {
        render { FileRow(onMoreClick = {}) }

        moreButton().assertExists()
    }

    @Test
    fun `row without a more callback has no more button`() {
        render { FileRow(onMoreClick = null) }

        moreButton().assertDoesNotExist()
    }

    @Test
    fun `row more button opens the actions instead of the file`() {
        render { FileRow(onMoreClick = { moreClicks++ }) }

        moreButton().performClick()

        composeTestRule.runOnIdle {
            moreClicks shouldBe 1
            clicks shouldBe 0
        }
    }

    @Test
    fun `tile with a more callback shows the more button`() {
        render { FileTile(onMoreClick = {}) }

        moreButton().assertExists()
    }

    @Test
    fun `tile without a more callback has no more button`() {
        render { FileTile(onMoreClick = null) }

        moreButton().assertDoesNotExist()
    }

    @Test
    fun `tile more button opens the actions instead of the file`() {
        render { FileTile(onMoreClick = { moreClicks++ }) }

        moreButton().performClick()

        composeTestRule.runOnIdle {
            moreClicks shouldBe 1
            clicks shouldBe 0
        }
    }

    private fun readyState(
        openFilesOnTap: Boolean = true,
        selectedResultIds: Set<String> = emptySet(),
    ) = SearcherWorkspaceViewModel.State.Ready(
        selectionState = SearcherSelectionState(
            selectableResults = listOf(file, directory),
            selectedResultIds = selectedResultIds,
        ),
        openFilesOnTap = openFilesOnTap,
    )

    @Test
    fun `a file gets the more button while a tap opens files`() {
        readyState().showsMoreButton(file) shouldBe true
    }

    @Test
    fun `a folder never gets the more button`() {
        readyState().showsMoreButton(directory) shouldBe false
    }

    @Test
    fun `no more button while a tap shows the actions`() {
        readyState(openFilesOnTap = false).showsMoreButton(file) shouldBe false
    }

    @Test
    fun `no more button while selecting`() {
        readyState(selectedResultIds = setOf(directory.resultKey)).showsMoreButton(file) shouldBe false
    }
}
