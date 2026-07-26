package eu.darken.butler.workspace.ui.floatingbar

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Test
import testhelpers.BaseTest
import kotlin.coroutines.CoroutineContext

class FloatingBarStateTest : BaseTest() {

    /**
     * Swallows the launched block so a dispatch can be counted without needing a frame clock.
     * A dispatch means [FloatingBarState.triggerScrollCollapse] decided to start an animation.
     */
    private class CountingDispatcher : CoroutineDispatcher() {
        var dispatches: Int = 0
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
        }
    }

    private val dispatcher = CountingDispatcher()
    private val scope = CoroutineScope(dispatcher)

    private fun collapsedBar(): FloatingBarState = FloatingBarState(id = "bar").apply {
        runBlocking { scrollCollapseAnimatable.snapTo(1f) }
    }

    @Test
    fun `collapsing an expanded bar starts an animation`() {
        val bar = FloatingBarState(id = "bar")

        bar.triggerScrollCollapse(scope, 1f)

        dispatcher.dispatches shouldBe 1
    }

    @Test
    fun `expanding an already expanded bar does nothing`() {
        val bar = FloatingBarState(id = "bar")

        bar.triggerScrollCollapse(scope, 0f)

        dispatcher.dispatches shouldBe 0
    }

    @Test
    fun `repeated collapse requests do not restart the animation`() {
        val bar = collapsedBar()

        repeat(10) { bar.triggerScrollCollapse(scope, 1f) }

        dispatcher.dispatches shouldBe 0
    }

    @Test
    fun `reversing direction starts a new animation`() {
        val bar = collapsedBar()

        bar.triggerScrollCollapse(scope, 0f)

        dispatcher.dispatches shouldBe 1
    }

    @Test
    fun `a settled animation is not restarted`() {
        val bar = collapsedBar()

        bar.scrollCollapsedFraction shouldBe 1f
        bar.scrollCollapseAnimatable.targetValue shouldBe 1f
        bar.triggerScrollCollapse(scope, 1f)

        dispatcher.dispatches shouldBe 0
    }

    @Test
    fun `a scroll-collapse reset re-enables collapsing`() {
        val bar = collapsedBar()
        bar.triggerScrollCollapse(scope, 1f)
        dispatcher.dispatches shouldBe 0

        runBlocking { bar.scrollCollapseAnimatable.snapTo(0f) }
        bar.triggerScrollCollapse(scope, 1f)

        dispatcher.dispatches shouldBe 1
    }
}
