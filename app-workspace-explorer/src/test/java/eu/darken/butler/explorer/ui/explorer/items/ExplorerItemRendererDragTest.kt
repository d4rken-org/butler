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
 * Once a selection exists the long press belongs to the drag gesture and may no longer change the
 * selection. Rows route the press to the selection controller (guarded there), storage volumes
 * toggle directly from here.
 */
class ExplorerItemRendererDragTest : ComposeTest() {

    private val file = MockDataProvider.createMockRegularFile(FILE_NAME)
    private val otherFile = MockDataProvider.createMockRegularFile("selected.txt")
    private val storage = MockDataProvider.createMockStorageLocal(name = STORAGE_NAME)

    private var payloadRequests = 0
    private var toggles = 0

    @Test
    fun `long pressing a row arms the drag without toggling the selection`() {
        renderItem(item = file, selectedItems = setOf(otherFile))

        composeTestRule.onNodeWithText(FILE_NAME).performTouchInput { longClick() }

        // The payload request is the proof that the long press landed and still starts a drag, so
        // the untouched selection can't be a silently missed gesture.
        composeTestRule.runOnIdle {
            payloadRequests shouldBe 1
            toggles shouldBe 0
        }
    }

    @Test
    fun `long pressing a storage volume toggles only while nothing is selected`() {
        renderItem(item = storage, selectedItems = emptySet())

        composeTestRule.onNodeWithText(STORAGE_NAME).performTouchInput { longClick() }

        composeTestRule.runOnIdle { toggles shouldBe 1 }
    }

    @Test
    fun `long pressing a storage volume does nothing while a selection exists`() {
        renderItem(item = storage, selectedItems = setOf(otherFile))

        composeTestRule.onNodeWithText(STORAGE_NAME).performTouchInput { longClick() }

        composeTestRule.runOnIdle { toggles shouldBe 0 }
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
                    onItemLongClick = {},
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
