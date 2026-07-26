package eu.darken.butler.workspace.ui.floatingbar

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.BaseTest

class FloatingBarContentPaddingTest : BaseTest() {

    private val density = Density(2f)

    private fun topStack() = FloatingBarStackState(
        position = BarPosition.TOP,
        initialEdgePaddingPx = 0f,
        initialSystemBarInsetPx = 100f,
    )

    private fun bottomStack() = FloatingBarStackState(
        position = BarPosition.BOTTOM,
        initialEdgePaddingPx = 0f,
        initialSystemBarInsetPx = 40f,
    )

    @Test
    fun `padding tracks the stacks without the instance changing`() {
        val top = topStack()
        val padding = FloatingBarContentPadding(top, bottomStack(), density, start = 0.dp, end = 0.dp)

        padding.calculateTopPadding() shouldBe 50.dp
        padding.calculateBottomPadding() shouldBe 20.dp

        top.registerBar(FloatingBarState(id = "toolbar").apply { measuredHeight = 60f })

        padding.calculateTopPadding() shouldBe 80.dp
    }

    @Test
    fun `absent stacks contribute no padding`() {
        val padding = FloatingBarContentPadding(null, null, density, start = 0.dp, end = 0.dp)

        padding.calculateTopPadding() shouldBe 0.dp
        padding.calculateBottomPadding() shouldBe 0.dp
    }

    @Test
    fun `horizontal padding follows the layout direction`() {
        val padding = FloatingBarContentPadding(null, null, density, start = 8.dp, end = 4.dp)

        padding.calculateLeftPadding(LayoutDirection.Ltr) shouldBe 8.dp
        padding.calculateRightPadding(LayoutDirection.Ltr) shouldBe 4.dp
        padding.calculateLeftPadding(LayoutDirection.Rtl) shouldBe 4.dp
        padding.calculateRightPadding(LayoutDirection.Rtl) shouldBe 8.dp
    }
}
