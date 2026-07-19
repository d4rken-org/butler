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
}

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
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(modifier = Modifier.fillMaxSize())
    }
}
