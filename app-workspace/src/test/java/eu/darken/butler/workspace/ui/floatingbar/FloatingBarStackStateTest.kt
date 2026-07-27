package eu.darken.butler.workspace.ui.floatingbar

import androidx.compose.runtime.saveable.SaverScope
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
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

    // region Collapse state carried across compositions

    private fun collapsibleStack() = FloatingBarStackState(position = BarPosition.TOP).apply {
        registerBar(FloatingBarState(id = "toolbar", scrollBehavior = BarScrollBehavior.HideOnScroll))
        registerBar(FloatingBarState(id = "infobar", scrollBehavior = BarScrollBehavior.HideOnScroll))
        registerBar(FloatingBarState(id = "static", scrollBehavior = BarScrollBehavior.Static))
    }

    @Test
    fun `collapse targets are reported per bar, excluding static ones`() = runTest {
        val state = collapsibleStack()

        state.collapseTargets shouldBe mapOf("toolbar" to 0f, "infobar" to 0f)

        state.applyCollapse(mapOf("toolbar" to 1f, "infobar" to 1f))

        state.collapseTargets shouldBe mapOf("toolbar" to 1f, "infobar" to 1f)
    }

    /**
     * Bars in one stack diverge at rest - a reappearing bar snaps its own fraction to 0 while the
     * others stay collapsed - which is why the state is kept per bar rather than per stack.
     */
    @Test
    fun `bars keep their own fraction`() = runTest {
        val state = collapsibleStack()

        state.applyCollapse(mapOf("toolbar" to 1f, "infobar" to 0f))

        state.collapseTargets shouldBe mapOf("toolbar" to 1f, "infobar" to 0f)
    }

    @Test
    fun `a bar without a saved entry keeps its current fraction`() = runTest {
        val state = collapsibleStack()
        state.applyCollapse(mapOf("toolbar" to 1f, "infobar" to 1f))

        // A saved blob from a build that did not know "infobar" must not expand it
        state.applyCollapse(mapOf("toolbar" to 0f))

        state.collapseTargets shouldBe mapOf("toolbar" to 0f, "infobar" to 1f)
    }

    /**
     * The duplicate-key path itself has no unit test on purpose: it reads BuildConfigWrap, whose
     * reflection fallback cannot resolve the app's BuildConfig from a library module's unit test.
     * This pins the neighbouring case, that a repeat registration of the *same* bar is benign and
     * never mistaken for a wiring error.
     */
    @Test
    fun `re-registering the same bar instance is a no-op`() {
        val state = FloatingBarStackState(position = BarPosition.TOP)
        val bar = FloatingBarState(id = "toolbar")

        state.registerBar(bar)
        state.registerBar(bar)

        state.barStates.single() shouldBe bar
    }

    @Test
    fun `bars have to register before anything can be applied`() = runTest {
        val state = FloatingBarStackState(position = BarPosition.TOP)

        state.hasRegisteredBars shouldBe false
        state.collapseTargets shouldBe emptyMap()

        state.registerBar(FloatingBarState(id = "toolbar", scrollBehavior = BarScrollBehavior.HideOnScroll))

        state.hasRegisteredBars shouldBe true
    }

    // endregion

    // region Saver

    /**
     * `Saver.save` is a member extension - the saver is the dispatch receiver and can only be passed
     * implicitly, the [SaverScope] is the extension receiver. Naming the saver explicitly instead
     * offers it as the extension receiver, which does not resolve.
     */
    private fun savedBlob(state: FloatingBarStackState): List<Any> =
        with(FloatingBarStackState.Saver) { SaverScope { true }.save(state)!! }

    @Test
    fun `a restored stack keeps the geometry it was saved with`() {
        val state = FloatingBarStackState(
            position = BarPosition.BOTTOM,
            initialDefaultSpacingPx = 8f,
            initialEdgePaddingPx = 4f,
            initialContentGapPx = 16f,
            initialSystemBarInsetPx = 48f,
            initialImeExtraPx = 300f,
        )

        val restored = FloatingBarStackState.Saver.restore(savedBlob(state))!!

        restored.position shouldBe BarPosition.BOTTOM
        // System bar and IME insets are recomputed from WindowInsets, so only the edge padding is back
        restored.contentPaddingPx shouldBe 4f

        restored.registerBar(FloatingBarState(id = "toolbar").apply { measuredHeight = 100f })
        restored.registerBar(FloatingBarState(id = "infobar").apply { measuredHeight = 50f })

        // edge 4 + bar 100 + spacing 8 + bar 50 + content gap 16
        restored.contentPaddingPx shouldBe 178f
    }

    /**
     * Collapse persistence belongs to [WorkspaceBarCollapseStates]. A second copy of it here would
     * race the registry for the same bar, so the saved blob is pinned literally - re-adding bar state
     * has to fail this test rather than quietly compete.
     */
    @Test
    fun `the saver carries no per-bar state`() = runTest {
        val state = FloatingBarStackState(
            position = BarPosition.TOP,
            initialDefaultSpacingPx = 8f,
            initialEdgePaddingPx = 4f,
            initialContentGapPx = 16f,
        ).apply {
            registerBar(FloatingBarState(id = "toolbar", scrollBehavior = BarScrollBehavior.HideOnScroll))
        }
        state.applyCollapse(mapOf("toolbar" to 1f))

        savedBlob(state) shouldBe listOf("TOP", 8f, 4f, 16f)

        val restored = FloatingBarStackState.Saver.restore(savedBlob(state))!!
        restored.hasRegisteredBars shouldBe false
        restored.collapseTargets shouldBe emptyMap()
    }

    /**
     * The position is saved under its [BarPosition.persistedKey], so that reordering the constants
     * cannot turn a saved BOTTOM stack into a TOP one.
     */
    @Test
    fun `the saved position does not depend on the enum order`() {
        BarPosition.entries.forEach { position ->
            val saved = savedBlob(FloatingBarStackState(position = position))

            saved[0] shouldBe position.persistedKey
            FloatingBarStackState.Saver.restore(saved)!!.position shouldBe position
        }
    }

    @Test
    fun `an unknown position restores nothing, leaving the caller a fresh stack`() {
        FloatingBarStackState.Saver.restore(listOf("SIDE", 8f, 4f, 16f)) shouldBe null
    }

    // endregion
}
