package eu.darken.butler.explorer.ui.explorer.items

import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.explorer.ui.explorer.util.ExplorerSelectionState
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The first long press belongs to drag-select, so it must not start a platform drag; only a long
 * press on an already-selected item arms one - pressing an unselected item in selection mode extends
 * the selection instead. Whether the press changes the selection is decided by the selection
 * controller, which every item type - storage volumes included - is routed to instead of toggling
 * from the composition's own (stale within the frame) selection state.
 */
class ExplorerItemRendererDragTest : ComposeTest() {

    private val file = MockDataProvider.createMockRegularFile(FILE_NAME)
    private val otherFile = MockDataProvider.createMockRegularFile("selected.txt")
    private val storage = MockDataProvider.createMockStorageLocal(name = STORAGE_NAME)

    private var payloadRequests = 0
    private var toggles = 0
    private var longClicks = 0

    @Test
    fun `long pressing an already-selected row arms the drag`() {
        renderItem(item = file, selectedItems = setOf(file, otherFile))

        composeTestRule.onNodeWithText(FILE_NAME).performTouchInput { longClick() }

        // The payload request is the proof that the long press landed and still starts a drag, so
        // the untouched selection can't be a silently missed gesture.
        composeTestRule.runOnIdle {
            payloadRequests shouldBe 1
            longClicks shouldBe 1
            toggles shouldBe 0
        }
    }

    @Test
    fun `long pressing an unselected row does not arm the drag even while a selection exists`() {
        renderItem(item = file, selectedItems = setOf(otherFile))

        composeTestRule.onNodeWithText(FILE_NAME).performTouchInput { longClick() }

        // An unselected item in selection mode is claimed by drag-select to extend the selection,
        // never by the cross-pane drag.
        composeTestRule.runOnIdle {
            payloadRequests shouldBe 0
            longClicks shouldBe 1
            toggles shouldBe 0
        }
    }

    @Test
    fun `long pressing a row does not arm the drag while nothing is selected`() {
        renderItem(item = file, selectedItems = emptySet())

        composeTestRule.onNodeWithText(FILE_NAME).performTouchInput { longClick() }

        composeTestRule.runOnIdle {
            payloadRequests shouldBe 0
            longClicks shouldBe 1
            toggles shouldBe 0
        }
    }

    @Test
    fun `long pressing a storage volume goes through the selection controller`() {
        renderItem(item = storage, selectedItems = emptySet())

        composeTestRule.onNodeWithText(STORAGE_NAME).performTouchInput { longClick() }

        composeTestRule.runOnIdle {
            longClicks shouldBe 1
            toggles shouldBe 0
        }
    }

    @Test
    fun `long pressing a storage volume while a selection exists changes nothing here either`() {
        renderItem(item = storage, selectedItems = setOf(otherFile))

        composeTestRule.onNodeWithText(STORAGE_NAME).performTouchInput { longClick() }

        composeTestRule.runOnIdle {
            longClicks shouldBe 1
            toggles shouldBe 0
        }
    }

    private fun renderItem(item: ExplorerItem, selectedItems: Set<ExplorerItem>) {
        composeTestRule.setContent {
            PreviewWrapper {
                ExplorerItemRenderer(
                    item = item,
                    viewStyle = ExplorerViewStyle.List(),
                    state = MockDataProvider.createReadyState(
                        selectionState = ExplorerSelectionState(
                            selectableItems = setOf(file, otherFile, storage),
                            selectedItems = selectedItems,
                        ),
                    ),
                    isFocused = false,
                    onItemClick = {},
                    onItemLongClick = { longClicks++ },
                    onNavigate = {},
                    onToggleSelection = { toggles++ },
                    // A null payload keeps the platform drag out of Robolectric, the factory call
                    // itself is what shows the drag was armed.
                    dragPayloadFactory = {
                        payloadRequests++
                        null
                    },
                )
            }
        }
    }

    companion object {
        private const val FILE_NAME = "dragged.txt"
        private const val STORAGE_NAME = "Internal Storage"
    }
}
