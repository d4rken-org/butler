package eu.darken.butler.workspace.ui.floatingbar

import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.BaseTest

class FloatingBarStackStateTest : BaseTest() {

    // region IME-extra math

    @Test
    fun `no keyboard yields no ime extra`() {
        imeInsetExtraPx(imeBottomPx = 0f, navBottomPx = 48f) shouldBe 0f
    }

    @Test
    fun `gesture nav - extra is keyboard minus nav bar`() {
        imeInsetExtraPx(imeBottomPx = 900f, navBottomPx = 24f) shouldBe 876f
    }

    @Test
    fun `3-button nav - nav plus extra never double-counts the nav region`() {
        // The IME inset already spans the nav-bar region under 3-button navigation.
        val nav = 132f
        val ime = 1000f
        val extra = imeInsetExtraPx(imeBottomPx = ime, navBottomPx = nav)
        // Must reproduce the old WindowInsets.navigationBars.union(ime) result: max(nav, ime).
        (nav + extra) shouldBe maxOf(nav, ime)
    }

    @Test
    fun `ime smaller than nav clamps to zero`() {
        imeInsetExtraPx(imeBottomPx = 20f, navBottomPx = 48f) shouldBe 0f
    }

    // endregion

    // region IME extra is consumed by BOTH content padding and bar placement

    @Test
    fun `ime extra is added to content padding and bar offset`() {
        val state = FloatingBarStackState(
            position = BarPosition.BOTTOM,
            initialEdgePaddingPx = 0f,
            initialSystemBarInsetPx = 48f,
            initialImeExtraPx = 300f,
        )

        // No bars, no estimate -> base inset = system + ime extra + edge.
        state.contentPaddingPx shouldBe 348f

        // A single bar: nothing stacks after it, so its offset is the base inset too.
        state.registerBar(FloatingBarState(id = "test", initialVisible = true))
        state.getBarOffset(0) shouldBe 348f
    }

    @Test
    fun `without ime extra the inset is nav-bar only`() {
        val state = FloatingBarStackState(
            position = BarPosition.BOTTOM,
            initialEdgePaddingPx = 0f,
            initialSystemBarInsetPx = 48f,
            initialImeExtraPx = 0f,
        )
        state.contentPaddingPx shouldBe 48f
    }

    // endregion

    // region Inter-bar spacing only counts between visible bars

    private fun spacingTestState() = FloatingBarStackState(
        position = BarPosition.TOP,
        initialDefaultSpacingPx = 8f,
        initialEdgePaddingPx = 0f,
        initialContentGapPx = 0f,
        initialSystemBarInsetPx = 0f,
        initialImeExtraPx = 0f,
    )

    @Test
    fun `hidden trailing bar adds neither height nor spacing`() {
        val state = spacingTestState()
        state.registerBar(FloatingBarState(id = "toolbar", initialVisible = true).apply { measuredHeight = 100f })
        state.registerBar(FloatingBarState(id = "banners", initialVisible = false).apply { measuredHeight = 50f })

        state.contentPaddingPx shouldBe 100f
    }

    @Test
    fun `visible trailing bar adds height plus spacing`() {
        val state = spacingTestState()
        state.registerBar(FloatingBarState(id = "toolbar", initialVisible = true).apply { measuredHeight = 100f })
        state.registerBar(FloatingBarState(id = "banners", initialVisible = true).apply { measuredHeight = 50f })

        state.contentPaddingPx shouldBe 158f
    }

    @Test
    fun `hidden middle bar adds no spacing between its neighbors`() {
        val state = spacingTestState()
        state.registerBar(FloatingBarState(id = "toolbar", initialVisible = true).apply { measuredHeight = 100f })
        state.registerBar(FloatingBarState(id = "hidden", initialVisible = false).apply { measuredHeight = 50f })
        state.registerBar(FloatingBarState(id = "info", initialVisible = true).apply { measuredHeight = 24f })

        state.contentPaddingPx shouldBe 132f
    }

    // endregion
}
