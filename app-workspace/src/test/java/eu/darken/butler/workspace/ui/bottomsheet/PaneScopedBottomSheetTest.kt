package eu.darken.butler.workspace.ui.bottomsheet

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.LocalWorkspaceFocusRequest
import eu.darken.butler.workspace.ui.modal.LocalLayerActive
import eu.darken.butler.workspace.ui.modal.PaneLayer
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import eu.darken.butler.workspace.ui.modal.PaneLayerRank
import eu.darken.butler.workspace.ui.modal.WorkspaceBackHandler
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import testhelpers.TestApplication

/**
 * The screen qualifiers are load-bearing. The sheet anchors to the bottom of its pane, and the pane
 * here deliberately *is* the test root: inside an oversized box the sheet's lower half would hang
 * off the end of the root, where an injected touch lands on nothing while the call still reports
 * success.
 */
@Config(application = TestApplication::class, sdk = [34], qualifiers = "w400dp-h400dp")
class PaneScopedBottomSheetTest : ComposeTest() {

    /**
     * The sheet stays on screen for its exit transition after `visible` goes false. The content
     * behind it must stay inert for that whole time, not from the moment the flag flips.
     */
    @Test
    fun `the layer stays registered for the full exit transition`() {
        var visible by mutableStateOf(true)
        var contentActive: Boolean? = null

        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                        contentActive = LocalLayerActive.current
                    }
                    PaneScopedBottomSheet(visible = visible, onDismiss = {}) {
                        Text("sheet")
                    }
                }
            }
        }

        composeTestRule.mainClock.advanceTimeBy(1_000)
        contentActive shouldBe false

        composeTestRule.runOnIdle { visible = false }
        composeTestRule.mainClock.advanceTimeBy(50)
        contentActive shouldBe false

        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()
        contentActive shouldBe true
    }

    /**
     * The sheet's own back handler has to live inside its layer and last as long as it: gated on
     * `visible` it would switch off during the exit transition, while the page handlers below stay
     * disabled — and back would fall through to the activity's exit handler.
     */
    @Test
    fun `back stays with the sheet for the full exit transition`() {
        var visible by mutableStateOf(true)
        var sheetDismissed = 0
        var pageBackFired = false
        var activityBackFired = false
        var dispatcher: OnBackPressedDispatcher? = null

        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            PreviewWrapper {
                BackHandler(enabled = true) { activityBackFired = true }
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                        WorkspaceBackHandler { pageBackFired = true }
                    }
                    PaneScopedBottomSheet(visible = visible, onDismiss = { sheetDismissed++ }) {
                        Text("sheet")
                    }
                }
            }
        }

        composeTestRule.mainClock.advanceTimeBy(1_000)

        composeTestRule.runOnIdle { visible = false }
        composeTestRule.mainClock.advanceTimeBy(50)
        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }

        composeTestRule.runOnIdle {
            activityBackFired shouldBe false
            pageBackFired shouldBe false
            sheetDismissed shouldBe 1
        }

        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()

        // Once the sheet is gone the page owns back again
        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }
        composeTestRule.runOnIdle {
            pageBackFired shouldBe true
            activityBackFired shouldBe false
        }
    }

    @Test
    fun `a hidden sheet does not register a layer`() {
        var contentActive: Boolean? = null

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                        contentActive = LocalLayerActive.current
                    }
                    PaneScopedBottomSheet(visible = false, onDismiss = {}) {
                        Text("sheet")
                    }
                }
            }
        }

        composeTestRule.waitForIdle()
        contentActive shouldBe true
    }

    /**
     * The reported bug: content taller than the pane used to overflow the card and be clipped away,
     * with no way to reach it. [performScrollTo] before the assertion is the point — a bare
     * `performClick` on an off-screen control passes without the control ever being on screen.
     */
    @Test
    fun `content taller than the pane stays reachable`() {
        composeTestRule.setContent { Case { TallContent() } }

        composeTestRule.onNodeWithTag(itemTag(ITEM_COUNT - 1)).performScrollTo().assertIsDisplayed()

        val card = cardBounds()
        val last = itemBounds(ITEM_COUNT - 1)
        card.height shouldBeLessThanOrEqualTo paneBounds().height
        last.bottom shouldBeLessThanOrEqualTo card.bottom
    }

    @Test
    fun `short content does not stretch the sheet`() {
        composeTestRule.setContent {
            Case {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .testTag(itemTag(0)),
                )
            }
        }

        cardBounds().height shouldBeLessThan 120.dp
    }

    /**
     * Templates-shaped opt-out: a box with a height cap around its own scroller. A bare unbounded
     * lazy list would fail for reasons of its own and prove nothing about the opt-out.
     */
    @Test
    fun `content that bounds itself keeps its own scrolling`() {
        composeTestRule.setContent {
            Case(contentScroll = SheetContentScroll.ContentOwned) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp),
                ) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        repeat(ITEM_COUNT) { Item(it) }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(itemTag(ITEM_COUNT - 1)).performScrollTo().assertIsDisplayed()

        // The content's own cap decides the height, not the pane
        cardBounds().height shouldBeLessThan 260.dp
    }

    /**
     * The contract the opt-out exists for: content that scrolls itself without a height cap — the
     * shape every migrated call site used to have — must not end up inside the sheet's scroller as
     * well. Two unbounded scrollers on one axis is a crash, which is the loud failure direction the
     * parameter is documented to have.
     */
    @Test
    fun `content that scrolls itself without a height cap is left alone`() {
        composeTestRule.setContent {
            Case(contentScroll = SheetContentScroll.ContentOwned) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    repeat(ITEM_COUNT) { Item(it) }
                }
            }
        }

        composeTestRule.onNodeWithTag(itemTag(ITEM_COUNT - 1)).performScrollTo().assertIsDisplayed()
        cardBounds().height shouldBeLessThanOrEqualTo paneBounds().height
    }

    @Test
    fun `an upward drag scrolls the content instead of dismissing`() {
        var dismissals = 0
        composeTestRule.setContent { Case(onDismiss = { dismissals++ }) { TallContent() } }

        val before = itemBounds(0).top
        swipeContent(up = true)

        itemBounds(0).top shouldBeLessThan before
        dismissals shouldBe 0
    }

    /**
     * Also the fling policy: this swipe can carry the content all the way back to its top. Reaching
     * the top must end the gesture there, not continue into a dismissal the user never aimed for.
     */
    @Test
    fun `a downward drag scrolls toward the top and never dismisses`() {
        var dismissals = 0
        composeTestRule.setContent { Case(onDismiss = { dismissals++ }) { TallContent() } }

        composeTestRule.onNodeWithTag(itemTag(ITEM_COUNT - 1)).performScrollTo()
        val before = itemBounds(0).top
        swipeContent(up = false)

        itemBounds(0).top shouldBeGreaterThan before
        cardBounds().top shouldBe paneBounds().top
        dismissals shouldBe 0
    }

    /**
     * The way back: while the sheet is displaced, upward movement belongs to the sheet until it is
     * back at rest. Handing it to the content instead would scroll the content out from under a
     * finger that is still putting the sheet back.
     */
    @Test
    fun `an upward drag returns the displaced sheet before the content scrolls`() {
        var dismissals = 0
        composeTestRule.setContent { Case(onDismiss = { dismissals++ }) { TallContent() } }

        val out = with(composeTestRule.density) { 100.dp.toPx() }
        val back = with(composeTestRule.density) { 60.dp.toPx() }
        composeTestRule.onNodeWithTag(CARD_TAG).performTouchInput {
            down(Offset(centerX, height * 0.5f))
            // The content is at its top, so this displaces the sheet
            moveBy(Offset(0f, out), delayMillis = 32)
            // Less than the displacement, so all of it is owed to the sheet
            moveBy(Offset(0f, -back), delayMillis = 32)
            up()
        }
        composeTestRule.waitForIdle()

        itemBounds(0).top shouldBe handleBounds().bottom
        dismissals shouldBe 0
    }

    /**
     * A fling that runs out of content stops at the top. Carrying its leftover into the sheet would
     * dismiss it out from under a user who was only scrolling back up.
     */
    @Test
    fun `a fling that reaches the top of the content leaves the sheet alone`() {
        var dismissals = 0
        composeTestRule.setContent { Case(onDismiss = { dismissals++ }) { TallContent() } }

        // Four items' worth of scroll left, less than the fling will cover
        composeTestRule.onNodeWithTag(itemTag(ITEM_COUNT - 1)).performScrollTo()
        composeTestRule.onNodeWithTag(itemTag(4)).performScrollTo()

        val travel = with(composeTestRule.density) { 200.dp.toPx() }
        composeTestRule.onNodeWithTag(CARD_TAG).performTouchInput {
            // The drag itself stays within what the content can still consume
            swipeDown(startY = height * 0.15f, endY = height * 0.15f + travel)
        }
        composeTestRule.waitForIdle()

        // The fling really did carry the content to its top…
        itemBounds(0).top shouldBe handleBounds().bottom
        // …and stopped there instead of moving the sheet
        cardBounds().top shouldBe paneBounds().top
        dismissals shouldBe 0
    }

    /**
     * The handoff: one continuous drag that first exhausts the content's remaining scroll and then
     * keeps going hands the leftover to the sheet.
     */
    @Test
    fun `a drag continuing past the top of the content moves the sheet`() {
        var dismissals = 0
        composeTestRule.setContent { Case(onDismiss = { dismissals++ }) { TallContent() } }

        // Two items' worth of scroll left before the content reaches its top
        composeTestRule.onNodeWithTag(itemTag(ITEM_COUNT - 1)).performScrollTo()
        composeTestRule.onNodeWithTag(itemTag(2)).performScrollTo()

        val handoff = with(composeTestRule.density) { (ITEM_HEIGHT * 2 + 150.dp).toPx() }
        composeTestRule.onNodeWithTag(CARD_TAG).performTouchInput {
            down(Offset(centerX, height * 0.1f))
            repeat(DRAG_STEPS) { moveBy(Offset(0f, handoff / DRAG_STEPS), delayMillis = 32) }
            up()
        }
        composeTestRule.waitForIdle()

        dismissals shouldBe 1
    }

    /**
     * The pane host consumes the down of a press arriving while its pane is not the focused one,
     * so a tap only focuses the pane. The handle's `draggable` accepts a consumed down, so a drag
     * must keep working there — an unfocused pane's sheet can be dismissed with one gesture.
     */
    @Test
    fun `the handle still drags in an unfocused pane`() {
        var dismissals = 0
        var paneFocusRequests = 0

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalWorkspaceFocusRequest provides { paneFocusRequests++ },
            ) {
                Case(paneFocused = false, onDismiss = { dismissals++ }) { TallContent() }
            }
        }

        val travel = with(composeTestRule.density) { 150.dp.toPx() }
        composeTestRule.onNodeWithTag(HANDLE_TAG).performTouchInput {
            down(center)
            repeat(DRAG_STEPS) { moveBy(Offset(0f, travel / DRAG_STEPS), delayMillis = 32) }
            up()
        }
        composeTestRule.waitForIdle()

        dismissals shouldBe 1
        (paneFocusRequests > 0) shouldBe true
    }

    @Test
    fun `the handle dismisses even while the content is scrolled`() {
        var dismissals = 0
        composeTestRule.setContent { Case(onDismiss = { dismissals++ }) { TallContent() } }

        composeTestRule.onNodeWithTag(itemTag(ITEM_COUNT - 1)).performScrollTo()

        val travel = with(composeTestRule.density) { 150.dp.toPx() }
        composeTestRule.onNodeWithTag(HANDLE_TAG).performTouchInput {
            down(center)
            repeat(DRAG_STEPS) { moveBy(Offset(0f, travel / DRAG_STEPS), delayMillis = 32) }
            up()
        }
        composeTestRule.waitForIdle()

        dismissals shouldBe 1
    }

    /**
     * Grabbing the sheet again while it is springing back must hand it to the new gesture. The
     * spring runs in the coroutine of the node that ended the previous drag, and nothing cancels
     * that scope on its own — left running it keeps assigning the offset while the finger is
     * dragging the other way.
     */
    @Test
    fun `a second drag takes the sheet over from the spring back`() {
        var dismissals = 0
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent { Case(onDismiss = { dismissals++ }) { TallContent() } }
        composeTestRule.mainClock.advanceTimeBy(1_000)

        // Short and slow: under both the distance and the velocity threshold, so it springs back
        composeTestRule.onNodeWithTag(HANDLE_TAG).performTouchInput {
            down(center)
            repeat(DRAG_STEPS) { moveBy(Offset(0f, slowStep(60.dp)), delayMillis = 100) }
            up()
        }
        composeTestRule.mainClock.advanceTimeBy(50)

        val springingBack = handleBounds().top
        springingBack shouldBeLessThan 60.dp

        composeTestRule.onNodeWithTag(HANDLE_TAG).performTouchInput {
            down(center)
            repeat(DRAG_STEPS) { moveBy(Offset(0f, slowStep(80.dp)), delayMillis = 100) }
        }
        // Long enough for the previous spring to have finished, had it survived
        composeTestRule.mainClock.advanceTimeBy(1_000)

        // Followed the finger away from rest; the stale spring would have taken it back to zero.
        // The margin is well under the 80dp dragged, because touch slop eats the start of a drag.
        handleBounds().top shouldBeGreaterThan springingBack + 40.dp
        composeTestRule.onNodeWithTag(HANDLE_TAG).performTouchInput { up() }
        dismissals shouldBe 0
    }

    /**
     * A bounded nested scroller consumes its own gestures. The sheet may only take what that
     * scroller could not use, otherwise scrolling a list inside the sheet would throw it away.
     */
    @Test
    fun `scrolling a bounded nested list cannot dismiss the sheet`() {
        var dismissals = 0
        composeTestRule.setContent {
            Case(onDismiss = { dismissals++ }) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 150.dp)
                            .testTag(NESTED_TAG),
                    ) {
                        items((0 until ITEM_COUNT).toList()) { Item(it) }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(NESTED_TAG).performTouchInput {
            swipeUp(startY = height * 0.9f, endY = height * 0.1f)
        }
        composeTestRule.waitForIdle()
        // The list really did scroll, so the swipe below has somewhere to go inside it
        composeTestRule.onNodeWithTag(itemTag(5)).assertExists()

        composeTestRule.onNodeWithTag(NESTED_TAG).performTouchInput {
            swipeDown(startY = height * 0.1f, endY = height * 0.9f)
        }
        composeTestRule.waitForIdle()

        dismissals shouldBe 0
    }

    @Test
    fun `reopening the sheet starts at the top`() {
        var visible by mutableStateOf(true)
        composeTestRule.setContent { Case(visible = visible) { TallContent() } }

        composeTestRule.onNodeWithTag(itemTag(ITEM_COUNT - 1)).performScrollTo()
        itemBounds(0).top shouldBeLessThan cardBounds().top

        composeTestRule.runOnIdle { visible = false }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { visible = true }
        composeTestRule.waitForIdle()

        itemBounds(0).top shouldBeGreaterThan cardBounds().top
    }

    @Test
    fun `new content while the sheet stays open starts at the top`() {
        var contentKey by mutableStateOf("first")
        composeTestRule.setContent { Case(contentKey = contentKey) { TallContent() } }

        composeTestRule.onNodeWithTag(itemTag(ITEM_COUNT - 1)).performScrollTo()
        itemBounds(0).top shouldBeLessThan cardBounds().top

        composeTestRule.runOnIdle { contentKey = "second" }
        composeTestRule.waitForIdle()

        itemBounds(0).top shouldBeGreaterThan cardBounds().top
    }

    /**
     * A dialog opened from within the sheet recomposes it without replacing its content — the
     * position the user scrolled to has to survive that, or every rename attempt would throw the
     * conflict details back to the top.
     */
    @Test
    fun `a recomposition that keeps the same content does not reset the scroll`() {
        var dialogOpen by mutableStateOf(false)
        composeTestRule.setContent {
            // Read here rather than inside the content lambda, and passed on as a capture: that is
            // what makes the sheet itself recompose instead of only its content.
            val open = dialogOpen
            Case(contentKey = "same", onDismiss = { check(!open) }) {
                TallContent()
                if (open) Text("dialog")
            }
        }

        composeTestRule.onNodeWithTag(itemTag(ITEM_COUNT - 1)).performScrollTo()
        val scrolledTo = itemBounds(0).top

        composeTestRule.runOnIdle { dialogOpen = true }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { dialogOpen = false }
        composeTestRule.waitForIdle()

        itemBounds(0).top shouldBe scrolledTo
    }

    @Test
    fun `a sheet with a text field keeps its actions reachable`() {
        composeTestRule.setContent {
            Case(includeImePadding = true) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    var text by remember { mutableStateOf("") }
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(FIELD_TAG),
                        value = text,
                        onValueChange = { text = it },
                    )
                    repeat(ITEM_COUNT) { Item(it) }
                }
            }
        }

        composeTestRule.onNodeWithTag(FIELD_TAG).performClick()
        composeTestRule.onNodeWithTag(itemTag(ITEM_COUNT - 1)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag(FIELD_TAG).performScrollTo().assertIsDisplayed()
    }

    /**
     * Insets constrain the card only: the scrim has to keep covering the strip next to a side
     * navigation bar or a cutout.
     */
    @Test
    fun `insets constrain the card while the scrim stays full-pane`() {
        composeTestRule.setContent {
            Case(topInset = 48.dp, bottomInset = 32.dp) { TallContent() }
        }

        val pane = paneBounds()
        val scrim = composeTestRule.onNodeWithTag(PaneScopedBottomSheetDefaults.SCRIM_TEST_TAG)
            .getUnclippedBoundsInRoot()
        scrim.width shouldBe pane.width
        scrim.height shouldBe pane.height

        val card = cardBounds()
        card.top shouldBe pane.top + 48.dp
        card.bottom shouldBe pane.bottom

        composeTestRule.onNodeWithTag(itemTag(ITEM_COUNT - 1)).performScrollTo()
        itemBounds(ITEM_COUNT - 1).bottom shouldBeLessThanOrEqualTo card.bottom - 32.dp
    }

    /**
     * The sheet's own press observers have to inherit the pane's press gate, not just sit under a
     * boundary that happens to consume first.
     *
     * The pane-focus count is what shows the difference. Those observers read the down with
     * consumption ignored, so a scrim or card tap still asks for the pane after the boundary has
     * consumed it — the dismissal being withheld would be the boundary's doing, but a request
     * arriving would be the surface's own.
     */
    @Test
    fun `a sheet withholds the presses its pane withholds`() {
        var dismissals = 0
        var contentClicks = 0
        var paneFocusRequests = 0

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalWorkspaceFocusRequest provides { paneFocusRequests++ },
            ) {
                Case(allowPresses = { false }, onDismiss = { dismissals++ }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ITEM_HEIGHT)
                            .testTag(NESTED_TAG)
                            .clickable { contentClicks++ },
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag(SCRIM_TAG).performTouchInput { click(Offset(4f, 4f)) }
        composeTestRule.onNodeWithTag(NESTED_TAG).performClick()
        composeTestRule.waitForIdle()

        dismissals shouldBe 0
        contentClicks shouldBe 0
        paneFocusRequests shouldBe 0
    }

    @Composable
    private fun Case(
        visible: Boolean = true,
        onDismiss: () -> Unit = {},
        contentScroll: SheetContentScroll = SheetContentScroll.SheetOwned,
        contentKey: Any? = null,
        includeImePadding: Boolean = false,
        topInset: Dp = 0.dp,
        bottomInset: Dp = 0.dp,
        paneFocused: Boolean = true,
        allowPresses: () -> Boolean = { true },
        content: @Composable () -> Unit,
    ) {
        PreviewWrapper {
            PaneLayerHost(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(PANE_TAG),
                paneFocused = paneFocused,
                allowPresses = allowPresses,
            ) {
                PaneScopedBottomSheet(
                    visible = visible,
                    onDismiss = onDismiss,
                    topInset = topInset,
                    bottomInset = bottomInset,
                    includeImePadding = includeImePadding,
                    contentScroll = contentScroll,
                    contentKey = contentKey,
                    dragHandle = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(24.dp)
                                .testTag(HANDLE_TAG),
                        )
                    },
                    content = content,
                )
            }
        }
    }

    /**
     * A pane that hands the sheet an unbounded height — anything that wraps its content vertically
     * rather than filling a window — gives it nothing to bound against. A `weight` in a column with
     * an unbounded main axis measures to *zero*, so the sheet used to collapse to a stub card with
     * its entire content clipped away and no way to reach it.
     */
    @Test
    fun `an unbounded pane height does not collapse the sheet`() {
        composeTestRule.setContent {
            PreviewWrapper {
                UnboundedHeight {
                    PaneLayerHost(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(PANE_TAG),
                        paneFocused = true,
                    ) {
                        PaneScopedBottomSheet(
                            visible = true,
                            onDismiss = {},
                            dragHandle = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(24.dp)
                                        .testTag(HANDLE_TAG),
                                )
                            },
                        ) {
                            TallContent()
                        }
                    }
                }
            }
        }

        cardBounds().height shouldBeGreaterThan 100.dp
        composeTestRule.onNodeWithTag(itemTag(0)).assertIsDisplayed()
    }

    /** Measures its child with no height bound, the way a wrap-content container would. */
    @Composable
    private fun UnboundedHeight(content: @Composable () -> Unit) {
        Layout(content = content) { measurables, constraints ->
            val placeable = measurables.first().measure(
                constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity),
            )
            layout(placeable.width, placeable.height.coerceAtMost(constraints.maxHeight)) {
                placeable.place(0, 0)
            }
        }
    }

    @Composable
    private fun TallContent() {
        Column(modifier = Modifier.fillMaxWidth()) {
            repeat(ITEM_COUNT) { Item(it) }
        }
    }

    @Composable
    private fun Item(index: Int) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ITEM_HEIGHT)
                .testTag(itemTag(index)),
        )
    }

    /**
     * Swiped on the card rather than on the content: the content is taller than the root, and a
     * gesture aimed at the middle of an oversized node lands past the root's edge.
     */
    private fun swipeContent(up: Boolean) {
        composeTestRule.onNodeWithTag(CARD_TAG).performTouchInput {
            // Below the drag handle, so this is a content gesture and not a sheet gesture
            if (up) {
                swipeUp(startY = height * 0.9f, endY = height * 0.15f)
            } else {
                swipeDown(startY = height * 0.15f, endY = height * 0.9f)
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun paneBounds() = composeTestRule.onNodeWithTag(PANE_TAG).getUnclippedBoundsInRoot()

    private fun cardBounds() = composeTestRule.onNodeWithTag(CARD_TAG).getUnclippedBoundsInRoot()

    private fun handleBounds() = composeTestRule.onNodeWithTag(HANDLE_TAG).getUnclippedBoundsInRoot()

    /** One [DRAG_STEPS]th of [total], to be paired with a delay that keeps the fling velocity low. */
    private fun slowStep(total: Dp) = with(composeTestRule.density) { total.toPx() } / DRAG_STEPS

    private fun itemBounds(index: Int) =
        composeTestRule.onNodeWithTag(itemTag(index)).getUnclippedBoundsInRoot()

    companion object {
        private const val PANE_TAG = "pane.host"
        private const val HANDLE_TAG = "sheet.handle"
        private const val NESTED_TAG = "sheet.nested"
        private const val FIELD_TAG = "sheet.field"
        private val CARD_TAG = PaneScopedBottomSheetDefaults.CARD_TEST_TAG
        private val SCRIM_TAG = PaneScopedBottomSheetDefaults.SCRIM_TEST_TAG

        private val ITEM_HEIGHT = 60.dp
        private const val ITEM_COUNT = 20
        private const val DRAG_STEPS = 6

        private fun itemTag(index: Int) = "sheet.item.$index"
    }
}
