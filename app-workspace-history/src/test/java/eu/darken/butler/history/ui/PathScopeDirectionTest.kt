package eu.darken.butler.history.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.operations.history.HistoryFilter
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import testhelpers.ComposeTest

class PathScopeDirectionTest : ComposeTest() {

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    @Test
    fun `the path scope input lays its value out left to right in an rtl layout`() {
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                        PathScopeDialog(initialPath = PATH, onDismiss = {}, onApply = {})
                    }
                }
            }
        }

        val value = composeTestRule.onNode(hasSetTextAction(), useUnmergedTree = true).layout()
        value.layoutInput.text.text shouldBe PATH
        value.layoutInput.style.textDirection shouldBe TextDirection.Ltr
        value.layoutInput.style.textAlign shouldBe TextAlign.Right

        composeTestRule.onNode(hasText(LABEL) and !hasSetTextAction(), useUnmergedTree = true)
            .layout().layoutInput.style.textDirection shouldNotBe TextDirection.Ltr
        composeTestRule.onNodeWithText(DESCRIPTION, useUnmergedTree = true)
            .layout().layoutInput.style.textDirection shouldNotBe TextDirection.Ltr
    }

    @Test
    fun `a path scope chip lays its label out left to right in an rtl layout`() {
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    HistoryFilterChips(
                        filter = HistoryFilter(pathScopes = setOf(PATH)),
                        onRemoveOutcome = {},
                        onRemoveKind = {},
                        onRemovePathScope = {},
                        onAddFilter = {},
                    )
                }
            }
        }

        val label = composeTestRule.onNodeWithText(PATH, useUnmergedTree = true).layout()
        label.layoutInput.style.textDirection shouldBe TextDirection.Ltr
    }

    companion object {
        private const val PATH = "/storage/emulated/0/Download"
        private const val LABEL = "Path"
        private const val DESCRIPTION = "Show only operations affecting this path or its descendants."
    }
}
