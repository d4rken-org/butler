package eu.darken.butler.workspace.ui.workspaces.classic

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.ui.pagerFriendlyHorizontalScroll
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class PlaceholderCreationControllerTest : ComposeTest() {

    private val idA = Workspace.Id(Uuid.random())
    private val idB = Workspace.Id(Uuid.random())
    private val idC = Workspace.Id(Uuid.random())
    private val idD = Workspace.Id(Uuid.random())

    private fun info(id: Workspace.Id) = Workspace.Info(
        id = id,
        type = Workspace.Type.EXPLORER,
        title = "tab".toCaString(),
    )

    private inner class Harness {
        val interactions = MutableSharedFlow<Interaction>(extraBufferCapacity = 8)
        var pagerState: PagerState? = null
        var controller: PlaceholderCreationController? = null
        var created = 0

        fun dragStart() {
            interactions.tryEmit(DragInteraction.Start()) shouldBe true
            composeTestRule.waitForIdle()
        }

        fun dragStop() {
            interactions.tryEmit(DragInteraction.Stop(DragInteraction.Start())) shouldBe true
            composeTestRule.waitForIdle()
        }

        fun dragCancel() {
            interactions.tryEmit(DragInteraction.Cancel(DragInteraction.Start())) shouldBe true
            composeTestRule.waitForIdle()
        }

        /** Quiescence (50ms) + dwell (100ms) with margin. */
        fun advancePastDwell() = composeTestRule.mainClock.advanceTimeBy(300)

        fun advanceLong() = composeTestRule.mainClock.advanceTimeBy(2000)
    }

    private fun runHarness(
        initialWorkspaces: List<Workspace.Info>,
        block: Harness.(
            setWorkspaces: (List<Workspace.Info>) -> Unit,
            setBlocked: (Boolean) -> Unit,
            setDialog: (Boolean) -> Unit,
            scrollTo: (Int) -> Unit,
        ) -> Unit,
    ) {
        val harness = Harness()
        var workspaces by mutableStateOf(initialWorkspaces)
        var blocked by mutableStateOf(false)
        var dialog by mutableStateOf(false)
        var scrollCommand by mutableStateOf<Pair<Int, Int>?>(null)
        var scrollNonce = 0

        composeTestRule.setContent {
            PreviewWrapper {
                ControllerHarness(
                    workspaces = workspaces,
                    isInteractionBlocked = blocked,
                    hasBlockingDialog = dialog,
                    interactions = harness.interactions,
                    scrollCommand = scrollCommand,
                    onCreate = { harness.created++ },
                    onPagerState = { harness.pagerState = it },
                    onController = { harness.controller = it },
                )
            }
        }
        composeTestRule.waitForIdle()

        harness.block(
            { workspaces = it },
            { blocked = it },
            { dialog = it },
            { page ->
                scrollCommand = ++scrollNonce to page
                composeTestRule.waitForIdle()
            },
        )
    }

    @Test
    fun `completed drag settling on placeholder creates exactly once after dwell`() = runHarness(
        initialWorkspaces = listOf(info(idA), info(idB)),
    ) { _, _, _, scrollTo ->
        dragStart()
        scrollTo(2) // trailing placeholder page
        created shouldBe 0
        dragStop()

        advancePastDwell()
        created shouldBe 1
        controller!!.creationState shouldBe PlaceholderCreationState.Creating

        advanceLong()
        created shouldBe 1
    }

    @Test
    fun `real touch swipe onto placeholder creates after dwell`() {
        // End-to-end variant driving the pager's own interactionSource via injected touch input,
        // covering the real drag->idle->Stop->fling ordering the synthetic tests can't.
        var workspaces by mutableStateOf(listOf(info(idA), info(idB)))
        var created = 0
        var controller: PlaceholderCreationController? = null
        var pagerState: PagerState? = null

        composeTestRule.setContent {
            PreviewWrapper {
                ControllerHarness(
                    workspaces = workspaces,
                    isInteractionBlocked = false,
                    hasBlockingDialog = false,
                    interactions = null, // use the pager's real interaction stream
                    scrollCommand = 1 to 1, // park on the last real tab first (programmatic, no token)
                    onCreate = { created++ },
                    onPagerState = { pagerState = it },
                    onController = { controller = it },
                )
            }
        }
        composeTestRule.waitForIdle()
        pagerState!!.currentPage shouldBe 1

        composeTestRule.onNodeWithTag(PAGER_TAG).performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()
        pagerState!!.currentPage shouldBe 2

        composeTestRule.mainClock.advanceTimeBy(300)
        created shouldBe 1
        controller!!.creationState shouldBe PlaceholderCreationState.Creating
    }

    /**
     * Drives a real touch swipe that STARTS on horizontally scrollable page content, the case the
     * plain-Box harness above can't reach.
     *
     * @param contentWidthDp width of the content inside the scroll container. Narrower than the
     *   viewport leaves [ScrollState.maxValue] at 0 (nothing to scroll); much wider makes it scroll.
     */
    private fun swipeOverScrollableContent(
        contentWidthDp: Int,
        block: (pagerState: PagerState, scrollState: ScrollState, created: () -> Int) -> Unit,
    ) {
        var created = 0
        var pagerState: PagerState? = null
        var scrollState: ScrollState? = null

        composeTestRule.setContent {
            PreviewWrapper {
                ControllerHarness(
                    workspaces = listOf(info(idA), info(idB)),
                    isInteractionBlocked = false,
                    hasBlockingDialog = false,
                    interactions = null, // the pager's own interaction stream, not a synthetic one
                    scrollCommand = 1 to 1, // park on the last real tab (programmatic, no token)
                    onCreate = { created++ },
                    onPagerState = { pagerState = it },
                    onController = { },
                ) {
                    val pageScroll = rememberScrollState()
                    LaunchedEffect(pageScroll) { scrollState = pageScroll }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pagerFriendlyHorizontalScroll(pageScroll, isWorkspaceFocused = true),
                    ) {
                        Box(modifier = Modifier.width(contentWidthDp.dp))
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        pagerState!!.currentPage shouldBe 1

        composeTestRule.onNodeWithTag(PAGER_TAG).performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        block(pagerState!!, scrollState!!) { created }
    }

    @Test
    fun `swipe starting on unscrollable page content still creates`() = swipeOverScrollableContent(
        // Narrower than the viewport, so there is nothing to scroll.
        contentWidthDp = 1,
    ) { pagerState, scrollState, created ->
        // Regression guard: horizontalScroll() used to claim this drag despite having nothing to
        // scroll, then forward it to the pager as NESTED scroll. The page turned, but the pager's
        // own drag handler never ran, so no DragInteraction was emitted and the gesture token
        // never armed - the settle was declined and no workspace was created.
        scrollState.maxValue shouldBe 0
        pagerState.currentPage shouldBe 2

        composeTestRule.mainClock.advanceTimeBy(300)
        created() shouldBe 1
    }

    @Test
    fun `swipe starting on scrollable page content scrolls it instead of turning the page`() =
        swipeOverScrollableContent(
            // Far wider than the viewport, so the content owns the gesture.
            contentWidthDp = 4000,
        ) { pagerState, scrollState, created ->
            (scrollState.maxValue > 0) shouldBe true
            (scrollState.value > 0) shouldBe true
            pagerState.currentPage shouldBe 1

            composeTestRule.mainClock.advanceTimeBy(300)
            created() shouldBe 0
        }

    @Test
    fun `list shrink stranding pager on placeholder does not create`() = runHarness(
        initialWorkspaces = listOf(info(idA), info(idB), info(idC)),
    ) { setWorkspaces, _, _, scrollTo ->
        // User genuinely swipes to the LAST real tab...
        dragStart()
        scrollTo(2)
        dragStop()
        composeTestRule.mainClock.advanceTimeBy(100) // let the gesture settle-evaluate on the real tab
        pagerState!!.currentPage shouldBe 2

        // ...then that tab is closed. The pager inherits the placeholder page without any
        // user gesture — the exact phantom-recreation setup. Nothing may be created, no
        // matter how long the pager stays parked there.
        setWorkspaces(listOf(info(idA), info(idB)))
        composeTestRule.waitForIdle()
        advanceLong()

        created shouldBe 0
        controller!!.creationState shouldBe PlaceholderCreationState.Idle
    }

    @Test
    fun `programmatic scroll to placeholder does not create`() = runHarness(
        initialWorkspaces = listOf(info(idA), info(idB)),
    ) { _, _, _, scrollTo ->
        scrollTo(2) // no drag interaction — programmatic movement only
        pagerState!!.currentPage shouldBe 2

        advanceLong()

        created shouldBe 0
        controller!!.creationState shouldBe PlaceholderCreationState.Idle
    }

    @Test
    fun `canceled drag never creates even after visiting the placeholder`() = runHarness(
        initialWorkspaces = listOf(info(idA), info(idB)),
    ) { _, _, _, scrollTo ->
        // Cancel before any settle: token never arms.
        dragStart()
        scrollTo(2)
        dragCancel()
        advanceLong()
        created shouldBe 0
        controller!!.creationState shouldBe PlaceholderCreationState.Idle
    }

    @Test
    fun `cancel arriving while Visiting revokes the visit`() = runHarness(
        initialWorkspaces = listOf(info(idA), info(idB)),
    ) { _, _, _, scrollTo ->
        dragStart()
        scrollTo(2)
        dragStop()
        composeTestRule.mainClock.advanceTimeBy(60) // quiescence elapsed, dwell still pending
        controller!!.creationState shouldBe PlaceholderCreationState.Visiting

        dragCancel()
        advanceLong()

        created shouldBe 0
        controller!!.creationState shouldBe PlaceholderCreationState.Idle
    }

    @Test
    fun `swiping away during dwell cancels creation`() = runHarness(
        initialWorkspaces = listOf(info(idA), info(idB)),
    ) { _, _, _, scrollTo ->
        dragStart()
        scrollTo(2)
        dragStop()
        composeTestRule.mainClock.advanceTimeBy(60)
        controller!!.creationState shouldBe PlaceholderCreationState.Visiting

        dragStart()
        scrollTo(1)
        dragStop()
        advanceLong()

        created shouldBe 0
        controller!!.creationState shouldBe PlaceholderCreationState.Idle
    }

    @Test
    fun `overlay during dwell cancels and closing it does not re-arm`() = runHarness(
        initialWorkspaces = listOf(info(idA), info(idB)),
    ) { _, setBlocked, _, scrollTo ->
        dragStart()
        scrollTo(2)
        dragStop()
        composeTestRule.mainClock.advanceTimeBy(60)
        controller!!.creationState shouldBe PlaceholderCreationState.Visiting

        setBlocked(true)
        composeTestRule.waitForIdle()
        advanceLong()
        created shouldBe 0
        controller!!.creationState shouldBe PlaceholderCreationState.Idle

        // Overlay closes while the pager is still parked on the placeholder — the consumed
        // gesture must not come back to life.
        setBlocked(false)
        composeTestRule.waitForIdle()
        advanceLong()
        created shouldBe 0
    }

    @Test
    fun `list mutation during dwell cancels creation`() = runHarness(
        initialWorkspaces = listOf(info(idA), info(idB)),
    ) { setWorkspaces, _, _, scrollTo ->
        dragStart()
        scrollTo(2)
        dragStop()
        composeTestRule.mainClock.advanceTimeBy(60)
        controller!!.creationState shouldBe PlaceholderCreationState.Visiting

        // Same-size mutation (reorder) — pager never leaves the placeholder, but page
        // meanings changed, so the armed visit must be dropped.
        setWorkspaces(listOf(info(idB), info(idA)))
        composeTestRule.waitForIdle()
        advanceLong()

        created shouldBe 0
        controller!!.creationState shouldBe PlaceholderCreationState.Idle
    }

    @Test
    fun `manual click on placeholder creates`() = runHarness(
        initialWorkspaces = listOf(info(idA), info(idB)),
    ) { _, _, _, scrollTo ->
        scrollTo(2)

        composeTestRule.runOnIdle { controller!!.onPlaceholderClick() }
        composeTestRule.waitForIdle()

        created shouldBe 1
        controller!!.creationState shouldBe PlaceholderCreationState.Creating
    }

    @Test
    fun `limit dialog flow reaches Blocked and click retries`() = runHarness(
        initialWorkspaces = listOf(info(idA), info(idB)),
    ) { _, _, setDialog, scrollTo ->
        scrollTo(2)
        composeTestRule.runOnIdle { controller!!.onPlaceholderClick() }
        composeTestRule.waitForIdle()
        created shouldBe 1
        controller!!.creationState shouldBe PlaceholderCreationState.Creating

        setDialog(true)
        composeTestRule.waitForIdle()
        controller!!.creationState shouldBe PlaceholderCreationState.Failed

        setDialog(false)
        composeTestRule.waitForIdle()
        controller!!.creationState shouldBe PlaceholderCreationState.Blocked

        composeTestRule.runOnIdle { controller!!.onPlaceholderClick() }
        composeTestRule.waitForIdle()
        created shouldBe 2
        controller!!.creationState shouldBe PlaceholderCreationState.Creating
    }

    @Test
    fun `unrelated close during creation is not treated as success`() = runHarness(
        initialWorkspaces = listOf(info(idA), info(idB)),
    ) { setWorkspaces, _, _, scrollTo ->
        dragStart()
        scrollTo(2)
        dragStop()
        advancePastDwell()
        created shouldBe 1
        controller!!.creationState shouldBe PlaceholderCreationState.Creating

        // An unrelated tab closes while creation is in flight — no new id, not success.
        setWorkspaces(listOf(info(idA)))
        composeTestRule.waitForIdle()
        controller!!.creationState shouldBe PlaceholderCreationState.Creating

        // The created workspace finally lands — an id appears that wasn't there at trigger time.
        setWorkspaces(listOf(info(idA), info(idD)))
        composeTestRule.waitForIdle()
        controller!!.creationState shouldBe PlaceholderCreationState.Idle
        created shouldBe 1
    }

    @Test
    fun `integration - closing last focused tab clamps and never recreates (shrink before refocus)`() {
        var workspaces by mutableStateOf(listOf(info(idA), info(idB), info(idC)))
        var focused by mutableStateOf<Workspace.Id?>(idC)
        var created = 0
        val settled = mutableListOf<Workspace.Id>()
        var pagerState: PagerState? = null

        composeTestRule.setContent {
            PreviewWrapper {
                IntegrationHarness(
                    workspaces = workspaces,
                    focused = focused,
                    onSettled = { settled.add(it) },
                    onCreate = { created++ },
                    onPagerState = { pagerState = it },
                )
            }
        }
        composeTestRule.waitForIdle()
        pagerState!!.currentPage shouldBe 2

        // Repo state shrinks first; focus still points at the closed workspace (the real close
        // ordering). The coordinator must clamp off the placeholder and the controller must
        // never create — this is the reported bug end-to-end.
        workspaces = listOf(info(idA), info(idB))
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(2000)

        pagerState!!.currentPage shouldBe 1
        created shouldBe 0
        settled shouldBe emptyList()

        // Late MRU refocus lands on the tab the clamp already shows — no further movement.
        focused = idB
        composeTestRule.waitForIdle()
        pagerState!!.currentPage shouldBe 1
        created shouldBe 0
    }

    @Test
    fun `integration - refocus before shrink never touches the placeholder`() {
        var workspaces by mutableStateOf(listOf(info(idA), info(idB), info(idC)))
        var focused by mutableStateOf<Workspace.Id?>(idC)
        var created = 0
        var pagerState: PagerState? = null

        composeTestRule.setContent {
            PreviewWrapper {
                IntegrationHarness(
                    workspaces = workspaces,
                    focused = focused,
                    onSettled = { },
                    onCreate = { created++ },
                    onPagerState = { pagerState = it },
                )
            }
        }
        composeTestRule.waitForIdle()
        pagerState!!.currentPage shouldBe 2

        focused = idB
        composeTestRule.waitForIdle()
        pagerState!!.currentPage shouldBe 1

        workspaces = listOf(info(idA), info(idB))
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(2000)

        pagerState!!.currentPage shouldBe 1
        created shouldBe 0
    }
}

private const val PAGER_TAG = "pager"

@Composable
private fun ControllerHarness(
    workspaces: List<Workspace.Info>,
    isInteractionBlocked: Boolean,
    hasBlockingDialog: Boolean,
    interactions: MutableSharedFlow<Interaction>?,
    scrollCommand: Pair<Int, Int>?,
    onCreate: () -> Unit,
    onPagerState: (PagerState) -> Unit,
    onController: (PlaceholderCreationController) -> Unit,
    pageContent: @Composable () -> Unit = { Box(modifier = Modifier.fillMaxSize()) },
) {
    val pagerState = rememberPagerState(pageCount = { workspaces.size + 1 })
    LaunchedEffect(pagerState) { onPagerState(pagerState) }

    val controller = rememberPlaceholderCreationController(
        pagerState = pagerState,
        tabIds = workspaces.map { it.id },
        onDemandEnabled = true,
        isInteractionBlocked = isInteractionBlocked,
        hasBlockingDialog = hasBlockingDialog,
        onCreateRequested = onCreate,
        interactions = interactions ?: pagerState.interactionSource.interactions,
    )
    LaunchedEffect(controller) { onController(controller) }

    LaunchedEffect(scrollCommand) {
        scrollCommand?.let { (_, page) -> pagerState.animateScrollToPage(page) }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxSize()
            .testTag(PAGER_TAG),
    ) {
        pageContent()
    }
}

@Composable
private fun IntegrationHarness(
    workspaces: List<Workspace.Info>,
    focused: Workspace.Id?,
    onSettled: (Workspace.Id) -> Unit,
    onCreate: () -> Unit,
    onPagerState: (PagerState) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { workspaces.size + 1 })
    LaunchedEffect(pagerState) { onPagerState(pagerState) }
    val tabIds = workspaces.map { it.id }

    rememberPagerFocusCoordinator(
        pagerState = pagerState,
        tabIds = tabIds,
        focused = focused,
        isRestoring = false,
        isOverlayVisible = false,
        onSettled = onSettled,
    )
    rememberPlaceholderCreationController(
        pagerState = pagerState,
        tabIds = tabIds,
        onDemandEnabled = true,
        isInteractionBlocked = false,
        hasBlockingDialog = false,
        onCreateRequested = onCreate,
    )

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(modifier = Modifier.fillMaxSize())
    }
}
