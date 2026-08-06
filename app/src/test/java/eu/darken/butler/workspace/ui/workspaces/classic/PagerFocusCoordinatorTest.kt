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
}

private const val COORD_PAGER_TAG = "coordinatorPager"

@Composable
private fun TestHarness(
    workspaces: List<Workspace.Info>,
    focused: Workspace.Id?,
    onSettled: (Workspace.Id) -> Unit,
    onPagerState: (PagerState) -> Unit,
    trailingPages: Int = 0,
) {
    val pagerState = rememberPagerState(pageCount = { workspaces.size + trailingPages })
    LaunchedEffect(pagerState) { onPagerState(pagerState) }

    rememberPagerFocusCoordinator(
        pagerState = pagerState,
        tabIds = workspaces.map { it.id },
        focused = focused,
        isRestoring = false,
        isOverlayVisible = false,
        onSettled = onSettled,
    )

    HorizontalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxSize()
            .testTag(COORD_PAGER_TAG),
    ) {
        Box(modifier = Modifier.fillMaxSize())
    }
}
