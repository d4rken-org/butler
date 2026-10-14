package eu.darken.butler.explorer.ui.explorer.items

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel
import eu.darken.butler.explorer.ui.explorer.items.grid.RegularFileGrid
import eu.darken.butler.explorer.ui.explorer.items.row.RegularFileRow
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.explorer.ui.explorer.util.ExplorerSelectionState
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class FileMoreButtonTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val moreLabel get() = context.getString(R.string.explorer_file_more_actions_content_desc)

    private val file = MockDataProvider.createMockRegularFile(FILE_NAME)
    private val directory = MockDataProvider.createMockDirectory(DIRECTORY_NAME)

    private var clicks = 0
    private var moreClicks = 0
    private val moreClickedItems = mutableListOf<ExplorerItem.File>()

    private fun render(content: @Composable () -> Unit) {
        composeTestRule.setContent { PreviewWrapper { content() } }
    }

    private fun moreButton() = composeTestRule.onNodeWithContentDescription(moreLabel)

    private fun moreButtonCount() = composeTestRule
        .onAllNodesWithContentDescription(moreLabel)
        .fetchSemanticsNodes()
        .size

    @Composable
    private fun FileRow(
        modifier: Modifier = Modifier,
        density: ExplorerViewStyle.Density = ExplorerViewStyle.Density.COMFORTABLE,
        onMoreClick: (() -> Unit)?,
    ) = RegularFileRow(
        modifier = modifier,
        item = file,
        density = density,
        isSelected = false,
        onToggleSelection = {},
        onClick = { clicks++ },
        showSelection = false,
        onMoreClick = onMoreClick,
    )

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
    fun `more button leaves the row height unchanged at every density`() {
        render {
            Column {
                ExplorerViewStyle.Density.entries.forEach { density ->
                    FileRow(modifier = Modifier.testTag("plain:$density"), density = density, onMoreClick = null)
                    FileRow(modifier = Modifier.testTag("more:$density"), density = density, onMoreClick = {})
                }
            }
        }

        moreButtonCount() shouldBe ExplorerViewStyle.Density.entries.size
        ExplorerViewStyle.Density.entries.forEach { density ->
            heightOf("more:$density") shouldBe heightOf("plain:$density")
        }
    }

    private fun heightOf(tag: String): Int = composeTestRule
        .onNodeWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNode()
        .size
        .height

    @Composable
    private fun FileTile(onMoreClick: (() -> Unit)?) = Box(Modifier.size(200.dp)) {
        RegularFileGrid(
            item = file,
            density = ExplorerViewStyle.Density.COMFORTABLE,
            isSelected = false,
            onToggleSelection = {},
            onClick = { clicks++ },
            showSelection = false,
            onMoreClick = onMoreClick,
        )
    }

    @Test
    fun `tile without a more callback has no more button`() {
        render { FileTile(onMoreClick = null) }

        moreButton().assertDoesNotExist()
    }

    @Test
    fun `tile with a more button still shows the file size`() {
        render { FileTile(onMoreClick = {}) }

        moreButton().assertExists()
        composeTestRule
            .onNodeWithText(formatFileSize(context, file.lookup.size!!, shortFormat = false), useUnmergedTree = true)
            .assertExists()
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

    private fun renderItem(
        item: ExplorerItem,
        mode: ExplorerViewStyle.Mode = ExplorerViewStyle.Mode.LIST,
        directOpenEnabled: Boolean = true,
        selectedItems: Set<ExplorerItem> = emptySet(),
    ) {
        val state: ExplorerWorkspaceViewModel.State = MockDataProvider.createReadyState(
            selectionState = ExplorerSelectionState(
                selectableItems = setOf(file, directory),
                selectedItems = selectedItems,
            ),
        ).copy(directOpenEnabled = directOpenEnabled)
        render {
            Box(Modifier.size(200.dp)) {
                ExplorerItemRenderer(
                    item = item,
                    viewStyle = ExplorerViewStyle(mode = mode),
                    state = state,
                    isFocused = false,
                    onItemClick = { clicks++ },
                    onItemLongClick = {},
                    onItemMoreClick = { moreClickedItems += it },
                    onNavigate = {},
                    onToggleSelection = {},
                )
            }
        }
    }

    @Test
    fun `renderer gives a file row the more button while tap opens files`() {
        renderItem(file)

        moreButton().performClick()

        composeTestRule.runOnIdle {
            moreClickedItems shouldBe listOf(file)
            clicks shouldBe 0
        }
    }

    @Test
    fun `renderer gives a file tile the more button while tap opens files`() {
        renderItem(file, mode = ExplorerViewStyle.Mode.GRID)

        moreButton().performClick()

        composeTestRule.runOnIdle {
            moreClickedItems shouldBe listOf(file)
            clicks shouldBe 0
        }
    }

    @Test
    fun `renderer shows no more button while tap shows the actions`() {
        renderItem(file, directOpenEnabled = false)

        moreButton().assertDoesNotExist()
    }

    @Test
    fun `renderer shows no more button on a folder`() {
        renderItem(directory)

        moreButton().assertDoesNotExist()
    }

    @Test
    fun `renderer shows no more button while selection checkboxes are showing`() {
        renderItem(file, selectedItems = setOf(directory))

        moreButton().assertDoesNotExist()
    }

    companion object {
        private const val FILE_NAME = "report.pdf"
        private const val DIRECTORY_NAME = "Documents"
    }
}
