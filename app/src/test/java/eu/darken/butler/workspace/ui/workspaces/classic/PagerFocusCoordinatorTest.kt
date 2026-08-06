package eu.darken.butler.workspace.ui.workspaces.classic

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class PagerFocusCoordinatorTest : ComposeTest() {

    private val idA = Workspace.Id(Uuid.random())
    private val idB = Workspace.Id(Uuid.random())
    private val idC = Workspace.Id(Uuid.random())
    private val idD = Workspace.Id(Uuid.random())
    private val idE = Workspace.Id(Uuid.random())

    private fun info(id: Workspace.Id, ops: Int = 0) = Workspace.Info(
        id = id,
        type = Workspace.Type.EXPLORER,
        title = "tab".toCaString(),
        operationCount = ops,
    )

    @Test
    fun `focus change while idle scrolls pager`() {
        var capturedState: PagerState? = null
        var focused by mutableStateOf<Workspace.Id?>(idA)
        val workspaces = listOf(info(idA), info(idB), info(idC))

        composeTestRule.setContent {
            PreviewWrapper {
                TestHarness(
                    workspaces = workspaces,
                    focused = focused,
                    onSettled = { },
                    onPagerState = { capturedState = it },
                )
            }
        }

        composeTestRule.waitForIdle()
        capturedState!!.currentPage shouldBe 0

        focused = idC
        composeTestRule.waitForIdle()

        capturedState!!.currentPage shouldBe 2
    }

    @Test
    fun `info field churn does not move pager or fire onSettled`() {
        var capturedState: PagerState? = null
        var workspaces by mutableStateOf(listOf(info(idA, ops = 0), info(idB, ops = 0)))
        val settled = mutableListOf<Workspace.Id>()

        composeTestRule.setContent {
            PreviewWrapper {
                TestHarness(
                    workspaces = workspaces,
                    focused = idA,
                    onSettled = { settled.add(it) },
                    onPagerState = { capturedState = it },
                )
            }
        }
        composeTestRule.waitForIdle()
        capturedState!!.currentPage shouldBe 0

        workspaces = listOf(info(idA, ops = 0), info(idB, ops = 5))
        composeTestRule.waitForIdle()

        capturedState!!.currentPage shouldBe 0
        settled shouldBe emptyList()
    }

    @Test
    fun `coordinator-driven scroll does not echo onSettled`() {
        var capturedState: PagerState? = null
        var focused by mutableStateOf<Workspace.Id?>(idA)
        val workspaces = listOf(info(idA), info(idB), info(idC))
        val settled = mutableListOf<Workspace.Id>()

        composeTestRule.setContent {
            PreviewWrapper {
                TestHarness(
                    workspaces = workspaces,
                    focused = focused,
                    onSettled = { settled.add(it) },
                    onPagerState = { capturedState = it },
                )
            }
        }
        composeTestRule.waitForIdle()

        focused = idB
        composeTestRule.waitForIdle()

        capturedState!!.currentPage shouldBe 1
        settled shouldBe emptyList()
    }

    @Test
    fun `stale focus after list shrink clamps pager to last real page`() {
        var capturedState: PagerState? = null
        var workspaces by mutableStateOf(listOf(info(idA), info(idB), info(idC)))
        val settled = mutableListOf<Workspace.Id>()

        composeTestRule.setContent {
            PreviewWrapper {
                TestHarness(
                    workspaces = workspaces,
                    focused = idC,
                    trailingPages = 1,
                    onSettled = { settled.add(it) },
                    onPagerState = { capturedState = it },
                )
            }
        }
        composeTestRule.waitForIdle()
        capturedState!!.currentPage shouldBe 2

        // Closing the last tab: the list shrinks while focus still points at the removed
        // workspace. The pager inherits the trailing placeholder page and must be clamped
        // back into the real-tab range without reporting a user swipe.
        workspaces = listOf(info(idA), info(idB))
        composeTestRule.waitForIdle()

        capturedState!!.currentPage shouldBe 1
        settled shouldBe emptyList()
    }

    @Test
    fun `null focus after list shrink clamps pager to last real page`() {
        var capturedState: PagerState? = null
        var workspaces by mutableStateOf(listOf(info(idA), info(idB), info(idC)))
        var focused by mutableStateOf<Workspace.Id?>(idC)
        val settled = mutableListOf<Workspace.Id>()

        composeTestRule.setContent {
            PreviewWrapper {
                TestHarness(
                    workspaces = workspaces,
                    focused = focused,
                    trailingPages = 1,
                    onSettled = { settled.add(it) },
                    onPagerState = { capturedState = it },
                )
            }
        }
        composeTestRule.waitForIdle()
        capturedState!!.currentPage shouldBe 2

        focused = null
        workspaces = listOf(info(idA), info(idB))
        composeTestRule.waitForIdle()

        capturedState!!.currentPage shouldBe 1
        settled shouldBe emptyList()
    }

    @Test
    fun `list shrink with simultaneous refocus lands on the focused page`() {
        var capturedState: PagerState? = null
        var workspaces by mutableStateOf(listOf(info(idA), info(idB), info(idC)))
        var focused by mutableStateOf<Workspace.Id?>(idC)
        val settled = mutableListOf<Workspace.Id>()

        composeTestRule.setContent {
            PreviewWrapper {
                TestHarness(
                    workspaces = workspaces,
                    focused = focused,
                    trailingPages = 1,
                    onSettled = { settled.add(it) },
                    onPagerState = { capturedState = it },
                )
            }
        }
        composeTestRule.waitForIdle()
        capturedState!!.currentPage shouldBe 2

        // Close + MRU refocus arriving in the same recomposition: the focus sync must win,
        // not the clamp fallback.
        focused = idA
        workspaces = listOf(info(idA), info(idB))
        composeTestRule.waitForIdle()

        capturedState!!.currentPage shouldBe 0
        settled shouldBe emptyList()
    }

    @Test
    fun `tab list change without pager movement does not report a swipe`() {
        var capturedState: PagerState? = null
        var workspaces by mutableStateOf(listOf(info(idA), info(idB), info(idC), info(idD)))
        var focused by mutableStateOf<Workspace.Id?>(idC)
        val settled = mutableListOf<Workspace.Id>()

        composeTestRule.setContent {
            PreviewWrapper {
                TestHarness(
                    workspaces = workspaces,
                    focused = focused,
                    onSettled = { settled.add(it) },
                    onPagerState = { capturedState = it },
                )
            }
        }
        composeTestRule.waitForIdle()
        capturedState!!.currentPage shouldBe 2

        // Tab-limit recovery: the oldest tab is closed and a new one created in one operation.
        // Focus moves to the new workspace before the list carries it, and the close shifts every
        // remaining tab one index down — so the page the pager is parked on now holds a DIFFERENT
        // workspace than before, without the pager having moved. That must not look like a swipe.
        focused = idE
        workspaces = listOf(info(idB), info(idC), info(idD))
        composeTestRule.waitForIdle()

        capturedState!!.currentPage shouldBe 2
        settled shouldBe emptyList()
    }

    @Test
    fun `user swipe reports the swiped-to workspace`() {
        var capturedState: PagerState? = null
        val workspaces = listOf(info(idA), info(idB), info(idC))
        val settled = mutableListOf<Workspace.Id>()

        composeTestRule.setContent {
            PreviewWrapper {
                TestHarness(
                    workspaces = workspaces,
                    focused = idA,
                    onSettled = { settled.add(it) },
                    onPagerState = { capturedState = it },
                )
            }
        }
        composeTestRule.waitForIdle()
        capturedState!!.currentPage shouldBe 0

        composeTestRule.onNodeWithTag(COORD_PAGER_TAG).performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        capturedState!!.currentPage shouldBe 1
        settled shouldBe listOf(idB)
    }

    @Test
    fun `initial composition without a scroll does not report a swipe`() {
        var capturedState: PagerState? = null
        val workspaces = listOf(info(idA), info(idB), info(idC))
        val settled = mutableListOf<Workspace.Id>()

        // No focus to sync to, so the pager never moves. Its resting settle emission is not a
        // gesture and must not select the workspace that happens to sit at the current page.
        composeTestRule.setContent {
            PreviewWrapper {
                TestHarness(
                    workspaces = workspaces,
                    focused = null,
                    onSettled = { settled.add(it) },
                    onPagerState = { capturedState = it },
                )
            }
        }
        composeTestRule.waitForIdle()

        capturedState!!.currentPage shouldBe 0
        settled shouldBe emptyList()
    }

    @Test
    fun `swipe back to a previously clamped page still reports a swipe`() {
        var capturedState: PagerState? = null
        var workspaces by mutableStateOf(listOf(info(idA), info(idB), info(idC)))
        var focused by mutableStateOf<Workspace.Id?>(idC)
        val settled = mutableListOf<Workspace.Id>()

        composeTestRule.setContent {
            PreviewWrapper {
                TestHarness(
                    workspaces = workspaces,
                    focused = focused,
                    trailingPages = 1,
                    onSettled = { settled.add(it) },
                    onPagerState = { capturedState = it },
                )
            }
        }
        composeTestRule.waitForIdle()
        capturedState!!.currentPage shouldBe 2

        // Clamp: the list shrinks while focus still points at the removed workspace, so the
        // pager is corrected from page 2 to page 1.
        workspaces = listOf(info(idA), info(idB))
        composeTestRule.waitForIdle()
        capturedState!!.currentPage shouldBe 1

        // Focus resolves elsewhere and moves the pager off the clamped page.
        focused = idA
        composeTestRule.waitForIdle()
        capturedState!!.currentPage shouldBe 0
        settled shouldBe emptyList()

        // Swiping back onto the formerly clamped page is a real gesture — a leftover clamp
        // marker must not swallow it.
        composeTestRule.onNodeWithTag(COORD_PAGER_TAG).performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        capturedState!!.currentPage shouldBe 1
        settled shouldBe listOf(idB)
    }

    /**
     * The gate that decides whether a pane may consume system back.
     *
     * `settledPage` alone is not enough: it keeps naming the OUTGOING page for the whole fling, so
     * a back handler gated on it would still reach the tab being swiped away — the data-loss bug,
     * merely narrowed to a timing window. [isSettledOnPage] additionally requires the pager to be
     * idle.
     *
     * The scroll session is held open through [PagerState.scroll] — the very API a gesture and a
     * fling both scroll through — rather than caught at a guessed point of an animation, so the
     * case depends on no timing at all. The `isScrollInProgress` assertion keeps it non-vacuous: a
     * session that never opened fails the case instead of asserting nothing.
     */
    @Test
    fun `isSettledOnPage is false while a scroll session is open`() {
        var capturedState: PagerState? = null
        val workspaces = listOf(info(idA), info(idB), info(idC))
        var gate by mutableStateOf<CompletableDeferred<Unit>?>(null)

        composeTestRule.setContent {
            PreviewWrapper {
                TestHarness(
                    workspaces = workspaces,
                    focused = idA,
                    onSettled = { },
                    onPagerState = { capturedState = it },
                    heldScrollGate = gate,
                )
            }
        }
        composeTestRule.waitForIdle()
        val pagerState = capturedState!!
        pagerState.isSettledOnPage(0) shouldBe true

        val release = CompletableDeferred<Unit>()
        gate = release
        composeTestRule.waitForIdle()

        pagerState.isScrollInProgress shouldBe true
        // The conjunction is the point: settledPage still names the page the pager came from, so it
        // cannot be the only input to the gate.
        pagerState.settledPage shouldBe 0
        pagerState.isSettledOnPage(0) shouldBe false

        release.complete(Unit)
        composeTestRule.waitForIdle()

        pagerState.isSettledOnPage(0) shouldBe true
    }

    /**
     * Programmatic scrolls can overlap: the back handler on the trailing placeholder launches one
     * from its own scope, alongside the coordinator's focus sync. `animateScrollToPage` goes
     * through Compose's `MutatorMutex`, so the second cancels the first — and with a plain boolean
     * flag the finishing one would clear it out from under the other, letting the resulting settle
     * be reported as a user swipe.
     */
    @Test
    fun `a settle during an overlapping programmatic scroll is not reported as a swipe`() {
        var capturedState: PagerState? = null
        var capturedCoordinator: PagerFocusCoordinatorState? = null
        val workspaces = listOf(info(idA), info(idB), info(idC))
        val settled = mutableListOf<Workspace.Id>()
        var gate by mutableStateOf<CompletableDeferred<Unit>?>(null)

        composeTestRule.setContent {
            PreviewWrapper {
                TestHarness(
                    workspaces = workspaces,
                    focused = idA,
                    onSettled = { settled.add(it) },
                    onPagerState = { capturedState = it },
                    onCoordinator = { capturedCoordinator = it },
                    overlappingScrollTo = 1,
                    overlappingScrollGate = gate,
                )
            }
        }
        composeTestRule.waitForIdle()
        capturedState!!.currentPage shouldBe 0

        val release = CompletableDeferred<Unit>()
        gate = release
        composeTestRule.waitForIdle()

        // Inner scroll done, outer still in flight.
        capturedState!!.currentPage shouldBe 1
        capturedCoordinator!!.isAnimatingProgrammatically shouldBe true
        settled shouldBe emptyList()

        release.complete(Unit)
        composeTestRule.waitForIdle()

        capturedCoordinator!!.isAnimatingProgrammatically shouldBe false
        settled shouldBe emptyList()
    }

    /**
     * One swipe is two scroll episodes — the drag, then the fling/snap after it — and both settle on
     * the same page, so a settle is only reported when the page actually changed. That record has to
     * follow programmatic movement too: a jump can flip isScrollInProgress on and off without
     * snapshotFlow observing it, so if the record were only kept for settles that pass the
     * scroll gate, the pager would move without it and the swipe back would look like a repeat.
     *
     * The user's route: swipe to a tab, pick the previous one from the tab manager, swipe again.
     */
    @Test
    fun `a swipe back onto a page left programmatically is reported again`() {
        var capturedState: PagerState? = null
        var focused by mutableStateOf<Workspace.Id?>(idA)
        val workspaces = listOf(info(idA), info(idB), info(idC))
        val settled = mutableListOf<Workspace.Id>()

        composeTestRule.setContent {
            PreviewWrapper {
                TestHarness(
                    workspaces = workspaces,
                    focused = focused,
                    onSettled = {
                        settled.add(it)
                        focused = it
                    },
                    onPagerState = { capturedState = it },
                )
            }
        }
        composeTestRule.waitForIdle()
        capturedState!!.currentPage shouldBe 0

        composeTestRule.onNodeWithTag(COORD_PAGER_TAG).performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()
        capturedState!!.currentPage shouldBe 1
        settled shouldBe listOf(idB)

        // Selected from the tab manager: focus moves without a gesture, and the coordinator jumps
        // the pager back rather than animating it.
        composeTestRule.runOnIdle { focused = idA }
        composeTestRule.waitForIdle()
        capturedState!!.currentPage shouldBe 0
        settled shouldBe listOf(idB)

        composeTestRule.onNodeWithTag(COORD_PAGER_TAG).performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        // Page and focus must not part ways: swallowing this leaves tab B on screen with tab A
        // focused, and everything keyed on focus then acts on the workspace the user cannot see.
        capturedState!!.currentPage shouldBe 1
        settled shouldBe listOf(idB, idB)
    }
}

private const val COORD_PAGER_TAG = "coordinatorPager"

@Composable
private fun TestHarness(
    workspaces: List<Workspace.Info>,
    focused: Workspace.Id?,
    onSettled: (Workspace.Id) -> Unit,
    onPagerState: (PagerState) -> Unit,
    trailingPages: Int = 0,
    onCoordinator: (PagerFocusCoordinatorState) -> Unit = {},
    overlappingScrollTo: Int? = null,
    overlappingScrollGate: CompletableDeferred<Unit>? = null,
    heldScrollGate: CompletableDeferred<Unit>? = null,
) {
    val pagerState = rememberPagerState(pageCount = { workspaces.size + trailingPages })
    LaunchedEffect(pagerState) { onPagerState(pagerState) }

    val coordinator = rememberPagerFocusCoordinator(
        pagerState = pagerState,
        tabIds = workspaces.map { it.id },
        focused = focused,
        isRestoring = false,
        isOverlayVisible = false,
        onSettled = onSettled,
    )
    LaunchedEffect(coordinator) { onCoordinator(coordinator) }

    // An open scroll session, held for as long as the gate is: exactly what a drag or a fling holds
    // while the pager is moving, minus the movement and the timing.
    LaunchedEffect(heldScrollGate) {
        val gate = heldScrollGate ?: return@LaunchedEffect
        pagerState.scroll { gate.await() }
    }

    // An inner programmatic scroll that runs to completion while the outer one is still open.
    LaunchedEffect(overlappingScrollGate) {
        val gate = overlappingScrollGate ?: return@LaunchedEffect
        val page = overlappingScrollTo ?: return@LaunchedEffect
        coordinator.asProgrammaticScroll {
            coordinator.asProgrammaticScroll { pagerState.animateScrollToPage(page) }
            gate.await()
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxSize()
            .testTag(COORD_PAGER_TAG),
    ) {
        Box(modifier = Modifier.fillMaxSize())
    }
}
