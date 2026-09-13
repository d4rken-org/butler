package eu.darken.butler.explorer.ui.explorer.elements

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.core.favorites.FavoriteItem
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * A favorite whose gateway lookup never completes still has to be reachable: it is the one a user
 * most likely wants gone, and the row no longer carries a remove button of its own.
 */
class FavoriteRowTest : ComposeTest() {

    private val stuck = FavoriteItem(
        path = LocalPath.build("/storage/emulated/0/Stuck"),
        state = FavoriteItem.State.Resolving,
    )

    private class Taps {
        var clicks = 0
        var longClicks = 0
    }

    private fun setRow(isSelectionMode: Boolean): Taps {
        val taps = Taps()
        composeTestRule.setContent {
            PreviewWrapper {
                FavoriteRow(
                    modifier = Modifier.testTag(ROW_TAG),
                    favorite = stuck,
                    isSelected = isSelectionMode,
                    isSelectionMode = isSelectionMode,
                    onClick = { taps.clicks++ },
                    onLongClick = { taps.longClicks++ },
                )
            }
        }
        return taps
    }

    @Test
    fun `a favorite that never resolves still enters selection on long-press`() {
        val taps = setRow(isSelectionMode = false)

        composeTestRule.onNodeWithTag(ROW_TAG).performSemanticsAction(SemanticsActions.OnLongClick)
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle { taps.longClicks shouldBe 1 }
    }

    @Test
    fun `a tap during selection toggles a favorite that never resolves`() {
        val taps = setRow(isSelectionMode = true)

        composeTestRule.onNodeWithTag(ROW_TAG).performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle { taps.clicks shouldBe 1 }
    }

    @Test
    fun `a plain tap on a favorite that never resolves does not navigate`() {
        val taps = setRow(isSelectionMode = false)

        composeTestRule.onNodeWithTag(ROW_TAG).performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle { taps.clicks shouldBe 0 }
    }

    companion object {
        private const val ROW_TAG = "explorer.favorites.row"
    }
}
