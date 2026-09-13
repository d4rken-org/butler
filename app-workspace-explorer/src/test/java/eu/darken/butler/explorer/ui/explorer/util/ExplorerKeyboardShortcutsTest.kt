package eu.darken.butler.explorer.ui.explorer.util

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyPress
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.actions.ExplorerActionBarItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The favorite-selection shortcuts, which the on-device QA lane cannot reach: Home has no keyboard.
 */
class ExplorerKeyboardShortcutsTest : ComposeTest() {

    private val favorite: APath<*> = LocalPath.build("/storage/emulated/0/Download")
    private val item: ExplorerItem = MockDataProvider.createMockShortcut()

    private val favoriteActions = listOf(
        ExplorerActionBarItem.Common.RemoveFromFavorites(listOf(favorite)),
        ExplorerActionBarItem.Common.RenameFavorite(favorite),
    )
    private val itemActions = listOf(
        ExplorerActionBarItem.Directory.Delete(),
        ExplorerActionBarItem.Directory.Rename(),
    )

    private class Recorded {
        val executed = mutableListOf<ExplorerActionBarItem>()
        var selectionsCleared = 0
        var focusesCleared = 0
        var permanentDeletes = 0
    }

    private fun setShortcuts(
        availableActions: List<ExplorerActionBarItem>,
        selectedItems: Set<ExplorerItem> = emptySet(),
        favoriteSelection: Set<APath<*>> = emptySet(),
        focusedItem: ExplorerItem? = null,
    ): Recorded {
        val recorded = Recorded()
        composeTestRule.setContent {
            PreviewWrapper {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .explorerKeyboardShortcuts(
                            availableActions = availableActions,
                            clipboardEntries = emptyList(),
                            selectedItems = selectedItems,
                            favoriteSelection = favoriteSelection,
                            focusedItem = focusedItem,
                            viewStyle = ExplorerViewStyle(),
                            gridColumns = 3,
                            trashEnabled = false,
                            onExecuteAction = { recorded.executed.add(it) },
                            onPaste = {},
                            onSelectAll = {},
                            onClearSelection = { recorded.selectionsCleared++ },
                            onClearFocus = { recorded.focusesCleared++ },
                            onNavigateToItem = {},
                            onGoBack = {},
                            onMoveFocusUp = {},
                            onMoveFocusDown = {},
                            onMoveFocusLeft = {},
                            onMoveFocusRight = {},
                            onMoveFocusToFirst = {},
                            onMoveFocusToLast = {},
                            onActivateFocusedItem = {},
                            onRenameFocusedItem = {},
                            onDeleteFocusedItem = {},
                            onPermanentDeleteFocusedItem = { recorded.permanentDeletes++ },
                        ),
                ) {}
            }
        }
        return recorded
    }

    private fun press(keyCode: Int, metaState: Int = 0) {
        composeTestRule.onRoot().performKeyPress(
            ComposeKeyEvent(
                NativeKeyEvent(0L, 0L, NativeKeyEvent.ACTION_DOWN, keyCode, 0, metaState),
            ),
        )
        composeTestRule.onRoot().performKeyPress(
            ComposeKeyEvent(
                NativeKeyEvent(0L, 0L, NativeKeyEvent.ACTION_UP, keyCode, 0, metaState),
            ),
        )
        composeTestRule.waitForIdle()
    }

    @Test
    fun `escape clears a favorite selection`() {
        val recorded = setShortcuts(favoriteActions, favoriteSelection = setOf(favorite))

        press(NativeKeyEvent.KEYCODE_ESCAPE)

        composeTestRule.runOnIdle { recorded.selectionsCleared shouldBe 1 }
    }

    /** Focusing a shortcut with the arrow keys does not end a favorite selection, so both can be live. */
    @Test
    fun `escape clears a favorite selection while an item holds focus`() {
        val recorded = setShortcuts(
            favoriteActions,
            favoriteSelection = setOf(favorite),
            focusedItem = item,
        )

        press(NativeKeyEvent.KEYCODE_ESCAPE)

        composeTestRule.runOnIdle {
            recorded.selectionsCleared shouldBe 1
            recorded.focusesCleared shouldBe 1
        }
    }

    @Test
    fun `F2 names the single selected favorite`() {
        val recorded = setShortcuts(favoriteActions, favoriteSelection = setOf(favorite))

        press(NativeKeyEvent.KEYCODE_F2)

        composeTestRule.runOnIdle {
            recorded.executed.single().shouldBeInstanceOf<ExplorerActionBarItem.Common.RenameFavorite>()
        }
    }

    @Test
    fun `delete removes the selected favorites`() {
        val recorded = setShortcuts(favoriteActions, favoriteSelection = setOf(favorite))

        press(NativeKeyEvent.KEYCODE_FORWARD_DEL)

        composeTestRule.runOnIdle {
            recorded.executed.single()
                .shouldBeInstanceOf<ExplorerActionBarItem.Common.RemoveFromFavorites>()
        }
    }

    /** Permanent deletion has no meaning for a bookmark. */
    @Test
    fun `shift+delete does nothing to a favorite selection`() {
        val recorded = setShortcuts(favoriteActions, favoriteSelection = setOf(favorite))

        press(NativeKeyEvent.KEYCODE_FORWARD_DEL, NativeKeyEvent.META_SHIFT_ON)

        composeTestRule.runOnIdle {
            recorded.executed.shouldBeEmpty()
            recorded.permanentDeletes shouldBe 0
        }
    }

    @Test
    fun `an item selection keeps escape, F2, delete and shift+delete as they were`() {
        val recorded = setShortcuts(itemActions, selectedItems = setOf(item))

        press(NativeKeyEvent.KEYCODE_ESCAPE)
        composeTestRule.runOnIdle { recorded.selectionsCleared shouldBe 1 }

        press(NativeKeyEvent.KEYCODE_F2)
        composeTestRule.runOnIdle {
            recorded.executed.last().shouldBeInstanceOf<ExplorerActionBarItem.Directory.Rename>()
        }

        press(NativeKeyEvent.KEYCODE_FORWARD_DEL)
        composeTestRule.runOnIdle {
            recorded.executed.last().shouldBeInstanceOf<ExplorerActionBarItem.Directory.Delete>()
        }

        press(NativeKeyEvent.KEYCODE_FORWARD_DEL, NativeKeyEvent.META_SHIFT_ON)
        composeTestRule.runOnIdle { recorded.permanentDeletes shouldBe 1 }
    }
}
