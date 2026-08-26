package eu.darken.butler.workspace.ui.workspaces.classic

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The signal that decides whether a press may act on the page under the finger.
 *
 * Driven through [PagerState.scroll] sessions and the pager's interaction stream rather than by
 * synthesized swipes: the gap this exists for is a sub-frame window between a drag's scroll session
 * and its fling's, which a gesture-level test could only reach by accident of harness timing.
 */
class PagerRestStateTest : ComposeTest() {

    /**
     * The latch has to start open. A holder cannot come into existence mid-gesture, and a closed
     * start would make every page press-inert for the quiescence window after each composition —
     * with no clock advance in between, which is exactly what a press injected right after
     * `setContent` gets ([ClassicPaneFocusFallbackTest]).
     */
    @Test
    fun `the pager rests on its page immediately after composition`() {
        var restState: PagerRestState? = null

        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            PreviewWrapper {
                TestHarness(onRestState = { restState = it })
            }
        }

        restState!!.isRestingOn(0) shouldBe true
    }

    @Test
    fun `the pager does not rest on a page while a scroll session is open`() {
        var pagerState: PagerState? = null
        var restState: PagerRestState? = null
        var session by mutableStateOf<ScrollSession?>(null)

        composeTestRule.setContent {
            PreviewWrapper {
                TestHarness(
                    session = session,
                    onPagerState = { pagerState = it },
                    onRestState = { restState = it },
                )
            }
        }
        composeTestRule.waitForIdle()
        restState!!.isRestingOn(0) shouldBe true

        session = ScrollSession()
        composeTestRule.waitForIdle()

        // Non-vacuity: a session that never opened would fail here instead of asserting nothing.
        pagerState!!.isScrollInProgress shouldBe true
        restState!!.isRestingOn(0) shouldBe false
    }

    /**
     * The gap between a drag's scroll session and the fling's, with the page visibly off-centre.
     * Both sessions belong to the user's one swipe, so the pager is not at rest in between.
     */
    @Test
    fun `the pager does not rest in the gap between drag and fling`() = inTheGap(dragBy = 60f) {
        // The offset is what makes this the easy half: the page is plainly not where it belongs.
        (it.currentPageOffsetFraction != 0f) shouldBe true
    }

    /**
     * The same gap, reached with the page exactly on its boundary — a drag that ends where it began,
     * or one whose fling has already snapped. Nothing about the pager's position says "mid-gesture"
     * here; only the fact that the idle has not held for the quiescence window does. This is the
     * case a predicate built on the page offset gets wrong.
     */
    @Test
    fun `the pager does not rest in a zero-offset gap between drag and fling`() = inTheGap(dragBy = 0f) {
        it.currentPageOffsetFraction shouldBe 0f
    }

    /**
     * One swipe as the pager reports it: a drag session, a beat in which nothing scrolls and the
     * finger lifts, then the fling's session. [assertGapSignature] pins what that beat looks like
     * from the outside.
     *
     * Every step in the gap is followed by an elapsed-time check against [REST_QUIESCENCE_MS]: the
     * harness spends a frame per step, so without it a slower step would let the latch reopen and
     * the case would silently stop being the one it is named after.
     */
    private fun inTheGap(dragBy: Float, assertGapSignature: (PagerState) -> Unit) {
        val interactions = MutableSharedFlow<Interaction>(extraBufferCapacity = 8)
        var pagerState: PagerState? = null
        var restState: PagerRestState? = null
        var session by mutableStateOf<ScrollSession?>(null)

        composeTestRule.setContent {
            PreviewWrapper {
                TestHarness(
                    interactions = interactions,
                    session = session,
                    onPagerState = { pagerState = it },
                    onRestState = { restState = it },
                )
            }
        }
        composeTestRule.waitForIdle()
        restState!!.isRestingOn(0) shouldBe true

        val drag = DragInteraction.Start()
        interactions.tryEmit(drag) shouldBe true
        val dragSession = ScrollSession(by = dragBy)
        session = dragSession
        composeTestRule.waitForIdle()
        pagerState!!.isScrollInProgress shouldBe true
        restState!!.isRestingOn(0) shouldBe false

        // The drag ends: its scroll session closes and the finger lifts, both before the fling's
        // session opens. The pager reports no scroll at all in here.
        val gapStart = composeTestRule.mainClock.currentTime
        dragSession.gate.complete(Unit)
        interactions.tryEmit(DragInteraction.Stop(drag)) shouldBe true
        composeTestRule.waitForIdle()

        pagerState!!.isScrollInProgress shouldBe false
        pagerState!!.settledPage shouldBe 0
        assertGapSignature(pagerState!!)
        stillInsideQuiescence(gapStart)
        restState!!.isRestingOn(0) shouldBe false

        // The fling picks the gesture up again, still inside the quiescence window.
        val flingSession = ScrollSession()
        session = flingSession
        composeTestRule.waitForIdle()
        stillInsideQuiescence(gapStart)
        pagerState!!.isScrollInProgress shouldBe true
        restState!!.isRestingOn(0) shouldBe false

        flingSession.gate.complete(Unit)
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(REST_QUIESCENCE_MS * 4)
        restState!!.isRestingOn(0) shouldBe true
    }

    private fun stillInsideQuiescence(since: Long) {
        (composeTestRule.mainClock.currentTime - since < REST_QUIESCENCE_MS) shouldBe true
    }
}

/** One held-open scroll session: the very API a drag and a fling both scroll through. */
private class ScrollSession(val by: Float = 0f) {
    val gate = CompletableDeferred<Unit>()
}

@Composable
private fun TestHarness(
    interactions: Flow<Interaction> = MutableSharedFlow(),
    session: ScrollSession? = null,
    onPagerState: (PagerState) -> Unit = {},
    onRestState: (PagerRestState) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    // Handed out during composition, not from an effect: the seeding case has to be observable
    // before the first frame runs.
    onPagerState(pagerState)
    onRestState(rememberPagerRestState(pagerState, interactions = interactions))

    // Keyed on the session's identity, so a new one restarts this — by which time the previous
    // scroll block has already returned, and cancelling it holds nothing open.
    LaunchedEffect(session) {
        if (session == null) return@LaunchedEffect
        pagerState.scroll {
            if (session.by != 0f) scrollBy(session.by)
            session.gate.await()
        }
    }

    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize())
    }
}
