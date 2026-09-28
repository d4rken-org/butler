package eu.darken.butler.common.compose

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.GraphicsMode
import testhelpers.ComposeTest

class InfoBlockPathDirectionTest : ComposeTest() {

    private fun renderPathEntryRtl() {
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    InfoBlock(
                        modifier = Modifier.width(400.dp),
                        entry = InfoEntry(
                            label = "Destination path",
                            value = PATH,
                            pairable = false,
                            valueStyle = InfoEntry.ValueStyle.PATH,
                        ),
                    )
                }
            }
        }
    }

    private fun valueLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        composeTestRule.onNodeWithText(PATH, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action!!
            .invoke(results)
        return results.single()
    }

    @Test
    fun `a path value lays out left to right on the start edge of an rtl layout`() {
        renderPathEntryRtl()

        val layout = valueLayout()
        layout.layoutInput.text.text shouldBe PATH
        layout.layoutInput.style.textDirection shouldBe TextDirection.Ltr
        layout.layoutInput.style.textAlign shouldBe TextAlign.Right
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun `the root slash of a path is drawn left of the first folder in an rtl layout`() {
        renderPathEntryRtl()

        val layout = valueLayout()
        layout.getBoundingBox(0).left shouldBeLessThan layout.getBoundingBox(1).left
    }

    companion object {
        private const val PATH = "/storage/emulated/0/Download"
    }
}
