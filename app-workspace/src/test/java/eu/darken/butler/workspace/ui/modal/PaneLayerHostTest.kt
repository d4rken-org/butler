package eu.darken.butler.workspace.ui.modal

import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.LocalTooltipsEnabled
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.LocalWorkspaceFocusRequest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import testhelpers.ComposeTest

class PaneLayerHostTest : ComposeTest() {

    private val hiddenFromAccessibility =
        SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility)

    /**
     * Advances the frozen clock until an emitted pulse has made it into the tree.
     *
     * A snapshot write that happens outside a frame — an emit from pointer dispatch — needs one
     * frame for the apply notification and a further one for the recomposition it triggers, and
     * whether the two collapse into a single frame depends on what else is already pending. The
     * handful of frames this costs is nothing against the durations the pulse tests assert.
     */
    private fun advanceUntilPulseComposed() {
        repeat(PULSE_COMPOSE_FRAMES) {
            val composed = composeTestRule
                .onAllNodesWithTag(TAG_PANE_FOCUS_PULSE)
                .fetchSemanticsNodes()
                .isNotEmpty()
            if (composed) return
            composeTestRule.mainClock.advanceTimeByFrame()
        }
    }

    @Test
    fun `the topmost layer is active and the ones below it are not`() {
        var contentActive: Boolean? = null
        var overlayActive: Boolean? = null

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                        contentActive = LocalLayerActive.current
                    }
                    PaneLayer(rank = PaneLayerRank.OVERLAY) {
                        overlayActive = LocalLayerActive.current
                    }
                }
            }
        }

        composeTestRule.waitForIdle()
        contentActive shouldBe false
        overlayActive shouldBe true
    }

    @Test
    fun `a single layer in a focused pane is active`() {
        var contentActive: Boolean? = null

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                        contentActive = LocalLayerActive.current
                    }
                }
            }
        }

        composeTestRule.waitForIdle()
        contentActive shouldBe true
    }

    @Test
    fun `no layer is active while the pane is not focused`() {
        var contentActive: Boolean? = null
        var overlayActive: Boolean? = null

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = false) {
                    PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                        contentActive = LocalLayerActive.current
                    }
                    PaneLayer(rank = PaneLayerRank.OVERLAY) {
                        overlayActive = LocalLayerActive.current
                    }
                }
            }
        }

        composeTestRule.waitForIdle()
        contentActive shouldBe false
        overlayActive shouldBe false
    }

    @Test
    fun `covered layers are hidden from accessibility and the top one is not`() {
        var showOverlay by mutableStateOf(false)

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneLayer(
                        modifier = Modifier.testTag(CONTENT_TAG),
                        rank = PaneLayerRank.CONTENT,
                        modal = false,
                    ) {}
                    if (showOverlay) {
                        PaneLayer(modifier = Modifier.testTag(OVERLAY_TAG), rank = PaneLayerRank.OVERLAY) {}
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(CONTENT_TAG).assert(!hiddenFromAccessibility)

        composeTestRule.runOnIdle { showOverlay = true }

        composeTestRule.onNodeWithTag(CONTENT_TAG).assert(hiddenFromAccessibility)
        composeTestRule.onNodeWithTag(OVERLAY_TAG).assert(!hiddenFromAccessibility)
    }

    /**
     * A flat Column of focusables passes even without containment, because focus search runs out of
     * candidates. A LazyColumn introduces its own focus-target boundary and is what actually
     * exercises the trap.
     */
    @Test
    fun `focus cannot escape the top layer into a lazy list behind it`() {
        val focusedItems = mutableSetOf<Int>()
        var focusManager: androidx.compose.ui.focus.FocusManager? = null
        val dialogFocus = FocusRequester()

        composeTestRule.setContent {
            focusManager = LocalFocusManager.current
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(List(20) { it }) { index ->
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .onFocusChanged { if (it.isFocused) focusedItems += index }
                                        .focusable(),
                                )
                            }
                        }
                    }
                    PaneLayer(rank = PaneLayerRank.OVERLAY) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .focusRequester(dialogFocus)
                                .focusable(),
                        )
                    }
                }
            }
        }

        composeTestRule.runOnIdle { dialogFocus.requestFocus() }
        focusedItems.clear()

        repeat(20) {
            composeTestRule.runOnIdle { focusManager!!.moveFocus(FocusDirection.Next) }
        }
        repeat(20) {
            composeTestRule.runOnIdle { focusManager!!.moveFocus(FocusDirection.Down) }
        }

        focusedItems shouldBe emptySet()
    }

    /** Control for the test above: without a layer on top, focus does reach the lazy list. */
    @Test
    fun `focus reaches the lazy list while nothing covers it`() {
        val focusedItems = mutableSetOf<Int>()
        var focusManager: androidx.compose.ui.focus.FocusManager? = null

        composeTestRule.setContent {
            focusManager = LocalFocusManager.current
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(List(20) { it }) { index ->
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .onFocusChanged { if (it.isFocused) focusedItems += index }
                                        .focusable(),
                                )
                            }
                        }
                    }
                }
            }
        }

        repeat(3) {
            composeTestRule.runOnIdle { focusManager!!.moveFocus(FocusDirection.Next) }
        }

        focusedItems.isNotEmpty() shouldBe true
    }

    @Test
    fun `focus is cleared from a layer once it is covered`() {
        var showOverlay by mutableStateOf(false)
        var contentHasFocus = false
        val contentFocus = FocusRequester()

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .focusRequester(contentFocus)
                                .onFocusChanged { contentHasFocus = it.isFocused }
                                .focusable(),
                        )
                    }
                    if (showOverlay) {
                        PaneLayer(rank = PaneLayerRank.OVERLAY) {}
                    }
                }
            }
        }

        composeTestRule.runOnIdle { contentFocus.requestFocus() }
        composeTestRule.runOnIdle { contentHasFocus shouldBe true }

        composeTestRule.runOnIdle { showOverlay = true }

        composeTestRule.runOnIdle { contentHasFocus shouldBe false }
    }

    @Test
    fun `focus is released when the pane holding it stops being focused`() {
        var paneFocused by mutableStateOf(true)
        var modalHasFocus = false
        val modalFocus = FocusRequester()

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = paneFocused) {
                    PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                        Box(modifier = Modifier.size(24.dp).focusable())
                    }
                    PaneLayer(rank = PaneLayerRank.OVERLAY) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .focusRequester(modalFocus)
                                .onFocusChanged { modalHasFocus = it.isFocused }
                                .focusable(),
                        )
                    }
                }
            }
        }

        composeTestRule.runOnIdle { modalFocus.requestFocus() }
        composeTestRule.runOnIdle { modalHasFocus shouldBe true }

        composeTestRule.runOnIdle { paneFocused = false }

        composeTestRule.runOnIdle { modalHasFocus shouldBe false }
    }

    /**
     * The trap follows pane focus, not just stack position — armed in an unfocused pane it would
     * keep focus hostage there and no other pane could take it.
     *
     * The modal has to be *holding* focus while its pane is inactive for the trap to be exercised
     * at all, which is exactly the state the pane-focus request is meanwhile trying to resolve. The
     * clock is held so that request stays unanswered and the layer keeps its focus for the duration
     * of the test; a handler is provided but never honours it.
     */
    @Test
    fun `a modal in an unfocused pane does not trap focus`() {
        var focusManager: androidx.compose.ui.focus.FocusManager? = null
        var coveredContentEverFocused = false
        var modalHasFocus = false
        var otherPaneHasFocus = false
        val modalFocus = FocusRequester()

        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            focusManager = LocalFocusManager.current
            PreviewWrapper {
                Row {
                    CompositionLocalProvider(
                        LocalWorkspaceFocusRequest provides { /* deliberately never honoured */ },
                    ) {
                        PaneLayerHost(paneFocused = false) {
                            PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .onFocusChanged { if (it.isFocused) coveredContentEverFocused = true }
                                        .focusable(),
                                )
                            }
                            PaneLayer(rank = PaneLayerRank.OVERLAY) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .focusRequester(modalFocus)
                                        .onFocusChanged { modalHasFocus = it.isFocused }
                                        .focusable(),
                                )
                            }
                        }
                    }
                    PaneLayerHost(paneFocused = true) {
                        PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .onFocusChanged { otherPaneHasFocus = it.isFocused }
                                    .focusable(),
                            )
                        }
                    }
                }
            }
        }

        composeTestRule.mainClock.advanceTimeBy(100)
        composeTestRule.runOnIdle { modalFocus.requestFocus() }
        composeTestRule.mainClock.advanceTimeByFrame()

        // Precondition: the modal really is holding focus while its pane is the inactive one
        composeTestRule.runOnIdle { modalHasFocus shouldBe true }

        // The trap must be disarmed, so focus can leave for the pane the user is actually in
        composeTestRule.runOnIdle { focusManager!!.moveFocus(FocusDirection.Next) }
        composeTestRule.mainClock.advanceTimeByFrame()

        composeTestRule.runOnIdle {
            otherPaneHasFocus shouldBe true
            modalHasFocus shouldBe false
            // ...and the content covered by the modal was never reachable
            coveredContentEverFocused shouldBe false
        }
    }

    /**
     * A modal arms its focus trap while its pane is the focused one, and only pane focus moving
     * away disarms it. Nothing in ordinary pane content requests pane focus — a press on a text
     * field or a list row is consumed long before it reaches a click handler — so without the
     * host's own press observer the previously focused pane would stay focused forever and keyboard
     * focus could never move to another pane at all.
     *
     * The press itself goes no further: arriving in a pane that is not the focused one, the host
     * consumes it, so the field is neither clicked nor given the cursor. The *next* press, once the
     * pane focus request has been honoured, is an ordinary press — and its focus request must
     * succeed, which works only because the first press already released the focus the other
     * pane's modal was holding (a refused-and-never-replayed field request used to leave the
     * cursor unplaceable).
     *
     * The focus requests are honoured by hand *after* the release is asserted: once the old pane
     * stops being focused, [PaneLayer] clears the modal's focus on its own, and an assertion made
     * only then could not tell the press observer's immediate release from that later cleanup.
     */
    @Test
    fun `pressing another pane hands the pane over and only the next press reaches the field`() {
        var focusedPane by mutableStateOf(PANE_A)
        var modalHasFocus = false
        var otherFieldHasFocus = false
        var otherFieldClicks = 0
        var paneBRequests = 0
        val modalFocus = FocusRequester()
        val otherFieldFocus = FocusRequester()

        composeTestRule.setContent {
            PreviewWrapper {
                Row {
                    CompositionLocalProvider(
                        LocalWorkspaceFocusRequest provides { },
                    ) {
                        PaneLayerHost(paneFocused = focusedPane == PANE_A) {
                            PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                                Box(modifier = Modifier.size(24.dp).focusable())
                            }
                            PaneLayer(rank = PaneLayerRank.OVERLAY) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .focusRequester(modalFocus)
                                        .onFocusChanged { modalHasFocus = it.isFocused }
                                        .focusable(),
                                )
                            }
                        }
                    }
                    CompositionLocalProvider(
                        // Recorded only; the test honours it by hand once the release is asserted
                        LocalWorkspaceFocusRequest provides { paneBRequests++ },
                    ) {
                        PaneLayerHost(paneFocused = focusedPane == PANE_B) {
                            PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                                // Consumes the press and asks for focus on up, like a text field
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag(OTHER_PANE_FIELD_TAG)
                                        .focusRequester(otherFieldFocus)
                                        .onFocusChanged { otherFieldHasFocus = it.isFocused }
                                        .focusable()
                                        .clickable {
                                            otherFieldClicks++
                                            otherFieldFocus.requestFocus()
                                        },
                                )
                            }
                        }
                    }
                }
            }
        }

        composeTestRule.runOnIdle { modalFocus.requestFocus() }
        composeTestRule.runOnIdle { modalHasFocus shouldBe true }

        composeTestRule.onNodeWithTag(OTHER_PANE_FIELD_TAG).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            // The request is still pending — pane A is untouched and still the focused one...
            focusedPane shouldBe PANE_A
            (paneBRequests > 0) shouldBe true
            // ...yet the modal already gave up its focus: the press observer released it, no
            // pane-focus change has happened that anything else could react to
            modalHasFocus shouldBe false
            // ...and the swallowed press neither clicked the field nor placed the cursor
            otherFieldClicks shouldBe 0
            otherFieldHasFocus shouldBe false
        }

        // The request comes back as pane focus, a round trip later
        composeTestRule.runOnIdle { focusedPane = PANE_B }

        composeTestRule.onNodeWithTag(OTHER_PANE_FIELD_TAG).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            // The pane is focused now, so this press is an ordinary one and lands
            otherFieldClicks shouldBe 1
            otherFieldHasFocus shouldBe true
        }
    }

    @Test
    fun `a press into an unfocused pane does not reach the content`() {
        var clicked = 0
        var paneFocusRequests = 0

        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceFocusRequest provides { paneFocusRequests++ },
                ) {
                    PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = false) {
                        PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag(PRESS_TARGET_TAG)
                                    .clickable { clicked++ },
                            )
                        }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            clicked shouldBe 0
            (paneFocusRequests > 0) shouldBe true
        }
    }

    @Test
    fun `a press inside the focused pane reaches the content`() {
        var clicked = 0

        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceFocusRequest provides { },
                ) {
                    PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                        PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag(PRESS_TARGET_TAG)
                                    .clickable { clicked++ },
                            )
                        }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle { clicked shouldBe 1 }
    }

    /**
     * The swallow keys off the authoritative pane focus state, not off having asked for it: until
     * the request comes back as pane focus, every further press keeps being consumed. A request
     * that is never honoured leaves the pane tap-inert rather than misclick-prone.
     */
    @Test
    fun `presses stay swallowed until pane focus arrives`() {
        var paneFocused by mutableStateOf(false)
        var clicked = 0
        var paneFocusRequests = 0

        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    // Deliberately not honoured from here; the test flips the state itself
                    LocalWorkspaceFocusRequest provides { paneFocusRequests++ },
                ) {
                    PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = paneFocused) {
                        PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag(PRESS_TARGET_TAG)
                                    .clickable { clicked++ },
                            )
                        }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performClick()
        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            clicked shouldBe 0
            paneFocusRequests shouldBe 2
        }

        composeTestRule.runOnIdle { paneFocused = true }

        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle { clicked shouldBe 1 }
    }

    /**
     * A second finger's down belongs to the same gesture as the first finger's, but hits its own
     * target — it must be swallowed individually. Consuming only the first down of a gesture would
     * let a tap slip through while another finger rests on the pane.
     */
    @Test
    fun `a second finger's tap into an unfocused pane is also swallowed`() {
        var clicked = 0

        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceFocusRequest provides { },
                ) {
                    PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = false) {
                        PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                            Box(
                                modifier = Modifier
                                    .size(96.dp)
                                    .testTag(PRESS_TARGET_TAG)
                                    .clickable { clicked++ },
                            )
                        }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performTouchInput {
            down(0, centerLeft)
        }
        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performTouchInput {
            down(1, centerRight)
            up(1)
        }
        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performTouchInput { up(0) }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle { clicked shouldBe 0 }
    }

    /**
     * The swallow is pointer input only, by design: assistive tech states its target explicitly,
     * so the misclick this guards against cannot happen there. A semantics click in an unfocused
     * pane keeps invoking the content's action directly — pinned here so a change to it is a
     * deliberate one.
     */
    @Test
    fun `a semantics click in an unfocused pane still activates the content`() {
        var clicked = 0

        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceFocusRequest provides { },
                ) {
                    PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = false) {
                        PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag(PRESS_TARGET_TAG)
                                    .clickable { clicked++ },
                            )
                        }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG)
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle { clicked shouldBe 1 }
    }

    /** Scroll and drag detectors accept a consumed down — only taps are swallowed. */
    @Test
    fun `an unfocused pane can still be scrolled by dragging`() {
        var listState: LazyListState? = null
        var paneFocusRequests = 0

        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceFocusRequest provides { paneFocusRequests++ },
                ) {
                    PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = false) {
                        PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                            val state = rememberLazyListState().also { listState = it }
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag(PRESS_TARGET_TAG),
                                state = state,
                            ) {
                                items(List(100) { it }) {
                                    Box(modifier = Modifier.fillMaxWidth().height(40.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            val scrolled = listState!!.firstVisibleItemIndex > 0 ||
                listState!!.firstVisibleItemScrollOffset > 0
            scrolled shouldBe true
            // The drag's press still asked for the pane
            (paneFocusRequests > 0) shouldBe true
        }
    }

    /**
     * The press observer stays installed across recompositions and reads the focus request handler
     * when a press arrives — a handler whose lambda identity changed in between must still be the
     * one that is invoked.
     */
    @Test
    fun `a press is delivered to the latest focus request handler`() {
        var useSecond by mutableStateOf(false)
        var firstRequests = 0
        var secondRequests = 0
        val first: () -> Unit = { firstRequests++ }
        val second: () -> Unit = { secondRequests++ }

        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceFocusRequest provides if (useSecond) second else first,
                ) {
                    PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = false) {
                        PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                            Box(modifier = Modifier.size(48.dp).testTag(PRESS_TARGET_TAG))
                        }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performClick()
        composeTestRule.runOnIdle { useSecond = true }
        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performClick()

        composeTestRule.runOnIdle {
            firstRequests shouldBe 1
            secondRequests shouldBe 1
        }
    }

    /**
     * The counterpart of the test above: handing focus over happens only for a press arriving in a
     * pane that is not the active one. A press inside the pane that already owns focus — on a
     * dialog's own scrim while typing in it, say — must leave that focus exactly where it is.
     */
    @Test
    fun `pressing inside the focused pane leaves its focus alone`() {
        var modalHasFocus = false
        var paneFocusRequests = 0
        val modalFocus = FocusRequester()

        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceFocusRequest provides { paneFocusRequests++ },
                ) {
                    PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                        PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                            Box(modifier = Modifier.size(24.dp).focusable())
                        }
                        PaneLayer(rank = PaneLayerRank.OVERLAY) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag(MODAL_SURFACE_TAG)
                                    .focusRequester(modalFocus)
                                    .onFocusChanged { modalHasFocus = it.isFocused }
                                    .focusable()
                                    .clickable { /* a scrim or surface that swallows the press */ },
                            )
                        }
                    }
                }
            }
        }

        composeTestRule.runOnIdle { modalFocus.requestFocus() }
        composeTestRule.runOnIdle { modalHasFocus shouldBe true }

        composeTestRule.onNodeWithTag(MODAL_SURFACE_TAG).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle { modalHasFocus shouldBe true }
    }

    /**
     * Focus can still arrive in an inactive pane after the fact — keyboard traversal into it, or a
     * child requesting focus asynchronously. That must pull pane focus along with it, otherwise
     * focus sits in one pane while back dispatch belongs to another.
     */
    @Test
    fun `focus arriving in an inactive pane asks for that pane to be focused`() {
        var paneFocusRequests = 0
        val modalFocus = FocusRequester()

        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceFocusRequest provides { paneFocusRequests++ },
                ) {
                    PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = false) {
                        PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                            Box(modifier = Modifier.size(24.dp).focusable())
                        }
                        PaneLayer(rank = PaneLayerRank.OVERLAY) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .focusRequester(modalFocus)
                                    .focusable(),
                            )
                        }
                    }
                }
            }
        }

        composeTestRule.runOnIdle { modalFocus.requestFocus() }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle { (paneFocusRequests > 0) shouldBe true }
    }

    @Test
    fun `focus is released when the pane it landed in stays inactive`() {
        var modalHasFocus = false
        val modalFocus = FocusRequester()

        composeTestRule.setContent {
            PreviewWrapper {
                // No handler: the request cannot be honoured, so the pane stays inactive
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = false) {
                    PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                        Box(modifier = Modifier.size(24.dp).focusable())
                    }
                    PaneLayer(rank = PaneLayerRank.OVERLAY) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .focusRequester(modalFocus)
                                .onFocusChanged { modalHasFocus = it.isFocused }
                                .focusable(),
                        )
                    }
                }
            }
        }

        composeTestRule.runOnIdle { modalFocus.requestFocus() }
        // Past the deadline for an answer that is never coming
        composeTestRule.mainClock.advanceTimeBy(2_000)
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle { modalHasFocus shouldBe false }
    }

    @Test
    fun `focus is kept when the pane focus request is honoured`() {
        var paneFocused by mutableStateOf(false)
        var modalHasFocus = false
        val modalFocus = FocusRequester()

        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceFocusRequest provides { paneFocused = true },
                ) {
                    PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = paneFocused) {
                        PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                            Box(modifier = Modifier.size(24.dp).focusable())
                        }
                        PaneLayer(rank = PaneLayerRank.OVERLAY) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .focusRequester(modalFocus)
                                    .onFocusChanged { modalHasFocus = it.isFocused }
                                    .focusable(),
                            )
                        }
                    }
                }
            }
        }

        composeTestRule.runOnIdle { modalFocus.requestFocus() }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            paneFocused shouldBe true
            modalHasFocus shouldBe true
        }
    }

    /**
     * The request travels through a screen action, the ViewModel and the page manager before it
     * comes back as pane focus. Honouring it a few frames late must still count — a layer that gave
     * up its focus by then would leave the pane with no focus at all, since nothing restores it.
     */
    @Test
    fun `focus is kept when the pane focus request is honoured a few frames later`() {
        var paneFocused by mutableStateOf(false)
        var modalHasFocus = false
        var paneFocusRequests = 0
        val modalFocus = FocusRequester()

        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceFocusRequest provides { paneFocusRequests++ },
                ) {
                    PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = paneFocused) {
                        PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                            Box(modifier = Modifier.size(24.dp).focusable())
                        }
                        PaneLayer(rank = PaneLayerRank.OVERLAY) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .focusRequester(modalFocus)
                                    .onFocusChanged { modalHasFocus = it.isFocused }
                                    .focusable(),
                            )
                        }
                    }
                }
            }
        }

        composeTestRule.mainClock.advanceTimeBy(100)
        composeTestRule.runOnIdle { modalFocus.requestFocus() }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.runOnIdle {
            modalHasFocus shouldBe true
            (paneFocusRequests > 0) shouldBe true
        }

        // Several frames pass while the request is still travelling through the workspace plumbing
        repeat(4) { composeTestRule.mainClock.advanceTimeByFrame() }
        composeTestRule.runOnIdle { modalHasFocus shouldBe true }

        // The answer finally arrives, well after any frame-count deadline would have expired
        composeTestRule.mainClock.advanceTimeBy(200)
        composeTestRule.runOnIdle { paneFocused = true }
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle { modalHasFocus shouldBe true }
    }

    /** The clear-on-covered effect must also catch focus arriving after the layer was covered. */
    @Test
    fun `focus arriving after a layer is covered is still released`() {
        var showOverlay by mutableStateOf(false)
        var contentHasFocus = false
        val contentFocus = FocusRequester()

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .focusRequester(contentFocus)
                                .onFocusChanged { contentHasFocus = it.isFocused }
                                .focusable(),
                        )
                    }
                    if (showOverlay) {
                        PaneLayer(rank = PaneLayerRank.OVERLAY) {}
                    }
                }
            }
        }

        composeTestRule.runOnIdle { showOverlay = true }
        composeTestRule.runOnIdle { contentFocus.requestFocus() }

        composeTestRule.runOnIdle { contentHasFocus shouldBe false }
    }

    @Test
    fun `a modal in one pane leaves the other pane's content active`() {
        var paneOneContentActive: Boolean? = null
        var paneTwoContentActive: Boolean? = null

        composeTestRule.setContent {
            PreviewWrapper {
                Row {
                    PaneLayerHost(paneFocused = true) {
                        PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                            paneOneContentActive = LocalLayerActive.current
                        }
                        PaneLayer(rank = PaneLayerRank.OVERLAY) {}
                    }
                    PaneLayerHost(paneFocused = true) {
                        PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                            paneTwoContentActive = LocalLayerActive.current
                        }
                    }
                }
            }
        }

        composeTestRule.waitForIdle()
        paneOneContentActive shouldBe false
        paneTwoContentActive shouldBe true
    }

    @Test
    fun `a pane-local child modal deactivates the parent workspace and its overlays`() {
        var parentContentActive: Boolean? = null
        var parentOverlayActive: Boolean? = null
        var childContentActive: Boolean? = null

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                        parentContentActive = LocalLayerActive.current
                    }
                    PaneLayer(rank = PaneLayerRank.OVERLAY) {
                        parentOverlayActive = LocalLayerActive.current
                    }
                    PaneLayer(rank = PaneLayerRank.CHILD_CONTENT) {
                        childContentActive = LocalLayerActive.current
                    }
                }
            }
        }

        composeTestRule.waitForIdle()
        parentContentActive shouldBe false
        parentOverlayActive shouldBe false
        childContentActive shouldBe true
    }

    @Test
    fun `a layer enclosing the top one stays reachable but is not itself active`() {
        var outerActive: Boolean? = null
        var innerActive: Boolean? = null

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneLayer(
                        modifier = Modifier.testTag(CONTENT_TAG),
                        rank = PaneLayerRank.CONTENT,
                        modal = false,
                    ) {
                        outerActive = LocalLayerActive.current
                        PaneLayer(modifier = Modifier.testTag(OVERLAY_TAG)) {
                            innerActive = LocalLayerActive.current
                        }
                    }
                }
            }
        }

        composeTestRule.waitForIdle()
        outerActive shouldBe false
        innerActive shouldBe true
        composeTestRule.onNodeWithTag(CONTENT_TAG).assert(!hiddenFromAccessibility)
    }

    /**
     * A swallowed press leaves the tap point without any feedback — the content's tap detectors
     * never start, so there is no ripple — and the pane border at the edge is easy to miss. The
     * host answers with a pulse of its own.
     *
     * The clock is frozen for the whole test: the pulse animation is finite, so it would otherwise
     * run to completion during synchronisation and be gone before the assert.
     */
    @Test
    fun `a swallowed press draws a pulse that fades out again`() {
        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceFocusRequest provides { },
                ) {
                    PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = false) {
                        PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                            Box(modifier = Modifier.size(96.dp).testTag(PRESS_TARGET_TAG))
                        }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performClick()
        advanceUntilPulseComposed()

        composeTestRule.onNodeWithTag(TAG_PANE_FOCUS_PULSE).assertExists()

        composeTestRule.mainClock.advanceTimeBy(PULSE_DURATION_MS + 100)
        composeTestRule.mainClock.advanceTimeByFrame()

        composeTestRule.onNodeWithTag(TAG_PANE_FOCUS_PULSE).assertDoesNotExist()
    }

    /** Nothing is swallowed in the focused pane, so nothing needs to be answered either. */
    @Test
    fun `a press inside the focused pane draws no pulse`() {
        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceFocusRequest provides { },
                ) {
                    PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                        PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                            Box(modifier = Modifier.size(96.dp).testTag(PRESS_TARGET_TAG))
                        }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performClick()
        // Far enough for a pulse to have composed, far short of one having faded out again
        repeat(PULSE_COMPOSE_FRAMES) { composeTestRule.mainClock.advanceTimeByFrame() }

        composeTestRule.onNodeWithTag(TAG_PANE_FOCUS_PULSE).assertDoesNotExist()
    }

    /**
     * Every pulse owns its animation for its whole life. A pulse finishing while a later one is
     * still running must take only its own state with it — sharing a slot would end the survivor
     * early or keep the finished one around.
     *
     * The pulses are started by real swallowed presses, the same way production emits them, while
     * the state stays with the test: the overlay is a single tagged node for all pulses, so their
     * individual lifetimes are only visible in the list.
     */
    @Test
    fun `an overlapping pulse lives out its own duration`() {
        val state = PaneFocusPulseState()

        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceFocusRequest provides { },
                    LocalPaneFocused provides false,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(PRESS_TARGET_TAG)
                            .requestPaneFocusOnPress(
                                consumeWhenUnfocused = true,
                                onPressSwallowed = { state.emit(it) },
                            ),
                    ) {
                        PaneFocusPulseOverlay(modifier = Modifier.matchParentSize(), state = state)
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performTouchInput {
            down(0, Offset(10f, 10f))
            up(0)
        }
        advanceUntilPulseComposed()
        composeTestRule.onNodeWithTag(TAG_PANE_FOCUS_PULSE).assertExists()

        // Well into the first pulse's run, but nowhere near its end
        composeTestRule.mainClock.advanceTimeBy(300)
        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performTouchInput {
            down(0, Offset(80f, 80f))
            up(0)
        }
        // Same lag for the second pulse, and nothing in the tree distinguishes it from the first.
        // A pulse that never composed would never animate either, and the final assert below would
        // still find it in the list.
        repeat(PULSE_COMPOSE_FRAMES) { composeTestRule.mainClock.advanceTimeByFrame() }
        composeTestRule.runOnIdle { state.pulses.size shouldBe 2 }

        // Past the first pulse's duration while the second is only a third into its own
        composeTestRule.mainClock.advanceTimeBy(200)
        composeTestRule.runOnIdle {
            state.pulses.map { it.position } shouldBe listOf(Offset(80f, 80f))
        }
        composeTestRule.onNodeWithTag(TAG_PANE_FOCUS_PULSE).assertExists()

        composeTestRule.mainClock.advanceTimeBy(PULSE_DURATION_MS + 100)
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.runOnIdle { state.pulses.isEmpty() shouldBe true }
        composeTestRule.onNodeWithTag(TAG_PANE_FOCUS_PULSE).assertDoesNotExist()
    }

    @Test
    fun `every swallowed down reports its own position`() {
        val swallowed = mutableListOf<Offset>()

        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceFocusRequest provides { },
                    LocalPaneFocused provides false,
                ) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .testTag(PRESS_TARGET_TAG)
                            .requestPaneFocusOnPress(
                                consumeWhenUnfocused = true,
                                onPressSwallowed = { swallowed += it },
                            ),
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performTouchInput {
            down(0, Offset(10f, 20f))
            up(0)
        }
        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performTouchInput {
            down(0, Offset(30f, 40f))
            up(0)
        }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            swallowed shouldBe listOf(Offset(10f, 20f), Offset(30f, 40f))
        }
    }

    /** The counterpart of the second-finger swallow: that down gets its own feedback too. */
    @Test
    fun `a second finger's down reports its own swallow`() {
        val swallowed = mutableListOf<Offset>()

        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceFocusRequest provides { },
                    LocalPaneFocused provides false,
                ) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .testTag(PRESS_TARGET_TAG)
                            .requestPaneFocusOnPress(
                                consumeWhenUnfocused = true,
                                onPressSwallowed = { swallowed += it },
                            ),
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performTouchInput {
            down(0, Offset(10f, 10f))
        }
        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performTouchInput {
            down(1, Offset(60f, 60f))
            up(1)
        }
        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performTouchInput { up(0) }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            swallowed shouldBe listOf(Offset(10f, 10f), Offset(60f, 60f))
        }
    }

    /**
     * The pure observers on dialogs and sheets swallow nothing, so there is nothing for them to
     * report — the pulse belongs to the press that got no feedback of its own.
     */
    @Test
    fun `an observing press reports no swallow`() {
        val swallowed = mutableListOf<Offset>()
        var paneFocusRequests = 0

        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceFocusRequest provides { paneFocusRequests++ },
                    LocalPaneFocused provides false,
                ) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .testTag(PRESS_TARGET_TAG)
                            .requestPaneFocusOnPress(
                                consumeWhenUnfocused = false,
                                onPressSwallowed = { swallowed += it },
                            ),
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            swallowed shouldBe emptyList()
            paneFocusRequests shouldBe 1
        }
    }

    /**
     * Like the focus request handler, the swallow callback is read when a press arrives instead of
     * keying the event loop on it — a changed lambda identity must not restart the loop mid-gesture.
     */
    @Test
    fun `a swallowed press is delivered to the latest swallow handler`() {
        var useSecond by mutableStateOf(false)
        var firstSwallows = 0
        var secondSwallows = 0
        val first: (Offset) -> Unit = { firstSwallows++ }
        val second: (Offset) -> Unit = { secondSwallows++ }

        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceFocusRequest provides { },
                    LocalPaneFocused provides false,
                ) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .testTag(PRESS_TARGET_TAG)
                            .requestPaneFocusOnPress(
                                consumeWhenUnfocused = true,
                                onPressSwallowed = if (useSecond) second else first,
                            ),
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performClick()
        composeTestRule.runOnIdle { useSecond = true }
        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performClick()

        composeTestRule.runOnIdle {
            firstSwallows shouldBe 1
            secondSwallows shouldBe 1
        }
    }

    /**
     * The overlay spans the whole pane and is the last child of the host, so it sits over
     * everything — purely as a drawing. A press landing while a pulse is still on screen must reach
     * the content exactly as it would otherwise.
     */
    @Test
    fun `content stays pressable while a pulse is still on screen`() {
        var paneFocused by mutableStateOf(false)
        var clicked = 0

        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceFocusRequest provides { },
                ) {
                    PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = paneFocused) {
                        PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                            Box(
                                modifier = Modifier
                                    .size(96.dp)
                                    .testTag(PRESS_TARGET_TAG)
                                    .clickable { clicked++ },
                            )
                        }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performClick()
        advanceUntilPulseComposed()
        composeTestRule.onNodeWithTag(TAG_PANE_FOCUS_PULSE).assertExists()

        composeTestRule.runOnIdle { paneFocused = true }
        composeTestRule.mainClock.advanceTimeByFrame()

        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performClick()
        composeTestRule.mainClock.advanceTimeByFrame()

        composeTestRule.runOnIdle { clicked shouldBe 1 }
        // The pulse was still running, so the overlay really was over the content
        composeTestRule.onNodeWithTag(TAG_PANE_FOCUS_PULSE).assertExists()
    }

    /**
     * A hovering cursor must not light up content the pane will not let it click. The affordances
     * are what goes away — the input path stays as it is, which the scroll test above guards.
     */
    @Test
    fun `an unfocused pane hands out no indication, no ripple and no tooltips`() {
        var paneFocused by mutableStateOf(false)
        var indication: Indication? = null
        var rippleConfiguration: RippleConfiguration? = null
        var tooltipsEnabled: Boolean? = null

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = paneFocused) {
                    PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                        indication = LocalIndication.current
                        rippleConfiguration = LocalRippleConfiguration.current
                        tooltipsEnabled = LocalTooltipsEnabled.current
                    }
                }
            }
        }

        val inertIndication = composeTestRule.runOnIdle {
            // A null configuration takes the whole ripple node away, state layers included
            rippleConfiguration shouldBe null
            tooltipsEnabled shouldBe false
            indication
        }

        composeTestRule.runOnIdle { paneFocused = true }

        composeTestRule.runOnIdle {
            rippleConfiguration shouldNotBe null
            tooltipsEnabled shouldBe true
            indication shouldNotBe inertIndication
        }
    }

    /**
     * With click-to-focus off the pane boundary is a pure observer again: the press lands where it
     * was aimed and only asks for the pane on the side, so there is nothing to answer with a pulse.
     */
    @Test
    fun `a press into an unfocused pane reaches the content while click to focus is off`() {
        composeTestRule.mainClock.autoAdvance = false
        var clicked = 0
        var paneFocusRequests = 0

        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceFocusRequest provides { paneFocusRequests++ },
                ) {
                    PaneLayerHost(
                        modifier = Modifier.fillMaxSize(),
                        paneFocused = false,
                        clickToFocus = false,
                    ) {
                        PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                            Box(
                                modifier = Modifier
                                    .size(96.dp)
                                    .testTag(PRESS_TARGET_TAG)
                                    .clickable { clicked++ },
                            )
                        }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performClick()
        // Far enough for a pulse to have composed, far short of one having faded out again
        repeat(PULSE_COMPOSE_FRAMES) { composeTestRule.mainClock.advanceTimeByFrame() }

        composeTestRule.onNodeWithTag(TAG_PANE_FOCUS_PULSE).assertDoesNotExist()
        composeTestRule.runOnIdle {
            clicked shouldBe 1
            (paneFocusRequests > 0) shouldBe true
        }
    }

    /** Nothing about the pane is muted while it answers clicks directly. */
    @Test
    fun `an unfocused pane keeps indication, ripple and tooltips while click to focus is off`() {
        var outerIndication: Indication? = null
        var indication: Indication? = null
        var rippleConfiguration: RippleConfiguration? = null
        var tooltipsEnabled: Boolean? = null

        composeTestRule.setContent {
            PreviewWrapper {
                outerIndication = LocalIndication.current
                PaneLayerHost(
                    modifier = Modifier.fillMaxSize(),
                    paneFocused = false,
                    clickToFocus = false,
                ) {
                    PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                        indication = LocalIndication.current
                        rippleConfiguration = LocalRippleConfiguration.current
                        tooltipsEnabled = LocalTooltipsEnabled.current
                    }
                }
            }
        }

        composeTestRule.runOnIdle {
            indication shouldBe outerIndication
            rippleConfiguration shouldNotBe null
            tooltipsEnabled shouldBe true
        }
    }

    /**
     * The barrier is what makes the pane inert to the feedback no ambient switch reaches: hover
     * elevation and pointer icons stop because the content stops being hit at all.
     */
    @Test
    fun `a hovering pointer raises the barrier over an unfocused pane`() {
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceFocusRequest provides { },
                ) {
                    PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = false) {
                        PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                            Box(modifier = Modifier.fillMaxSize().testTag(PRESS_TARGET_TAG))
                        }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(TAG_PANE_HOVER_BARRIER).assertDoesNotExist()

        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performMouseInput { moveTo(center) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_PANE_HOVER_BARRIER).assertExists()
    }

    /** A focused pane is meant to react to the cursor, so nothing may come between the two. */
    @Test
    fun `a hovering pointer raises no barrier over a focused pane`() {
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceFocusRequest provides { },
                ) {
                    PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                        PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                            Box(modifier = Modifier.fillMaxSize().testTag(PRESS_TARGET_TAG))
                        }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performMouseInput { moveTo(center) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_PANE_HOVER_BARRIER).assertDoesNotExist()
    }

    /** With the setting off an unfocused pane is directly interactive, hover feedback included. */
    @Test
    fun `a hovering pointer raises no barrier while click to focus is off`() {
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceFocusRequest provides { },
                ) {
                    PaneLayerHost(
                        modifier = Modifier.fillMaxSize(),
                        paneFocused = false,
                        clickToFocus = false,
                    ) {
                        PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                            Box(modifier = Modifier.fillMaxSize().testTag(PRESS_TARGET_TAG))
                        }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performMouseInput { moveTo(center) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_PANE_HOVER_BARRIER).assertDoesNotExist()
    }

    /**
     * Deliberate behaviour, pinned so it cannot change silently — this test is not describing a
     * defect. The barrier is the topmost hit sibling when the down arrives, and Compose resolves
     * the hit path once per gesture and keeps it, so a swipe that *starts* while a cursor rests in
     * an unfocused pane never reaches the content: the pane does not scroll. That is the accepted
     * cost of taking the content out of hit testing. The alternative — no barrier — was rejected
     * because it brings back the per-component hover feedback (Material's hover elevation above
     * all) that no ambient switch reaches.
     *
     * It needs a mouse and a touchscreen on the same device to happen at all, and it clears itself
     * with the very gesture that ran into it: see the test below.
     */
    @Test
    fun `a swipe starting under a hovering cursor does not scroll an unfocused pane`() {
        var listState: LazyListState? = null

        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceFocusRequest provides { },
                ) {
                    PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = false) {
                        PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                            val state = rememberLazyListState().also { listState = it }
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag(PRESS_TARGET_TAG),
                                state = state,
                            ) {
                                items(List(100) { it }) {
                                    Box(modifier = Modifier.fillMaxWidth().height(40.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performMouseInput { moveTo(center) }
        composeTestRule.waitForIdle()
        // Precondition: the swipe below really does start against a raised barrier
        composeTestRule.onNodeWithTag(TAG_PANE_HOVER_BARRIER).assertExists()

        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            val scrolled = listState!!.firstVisibleItemIndex > 0 ||
                listState!!.firstVisibleItemScrollOffset > 0
            scrolled shouldBe false
        }
    }

    /**
     * What keeps the limitation above from being more than a hiccup: the touch pointer that ran
     * into the barrier also lowers it — [trackNonTouchHover] treats touch as "not hovering" — and
     * only a fresh mouse move raises it again. So the next swipe scrolls, and the pane is never
     * left stuck.
     */
    @Test
    fun `a second swipe scrolls an unfocused pane once the first lowered the barrier`() {
        var listState: LazyListState? = null

        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceFocusRequest provides { },
                ) {
                    PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = false) {
                        PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                            val state = rememberLazyListState().also { listState = it }
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag(PRESS_TARGET_TAG),
                                state = state,
                            ) {
                                items(List(100) { it }) {
                                    Box(modifier = Modifier.fillMaxWidth().height(40.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performMouseInput { moveTo(center) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(TAG_PANE_HOVER_BARRIER).assertDoesNotExist()

        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            val scrolled = listState!!.firstVisibleItemIndex > 0 ||
                listState!!.firstVisibleItemScrollOffset > 0
            scrolled shouldBe true
        }
    }

    /**
     * The ordinary single-input path, which the barrier must never touch: with no cursor anywhere
     * near the pane there is nothing to raise it, and a finger scrolls an unfocused pane on the
     * first try. Asserted alongside the scroll, so a barrier that started appearing without a
     * hover would fail here rather than only in the mixed-input tests above.
     */
    @Test
    fun `a swipe scrolls an unfocused pane while nothing hovers it`() {
        var listState: LazyListState? = null

        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceFocusRequest provides { },
                ) {
                    PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = false) {
                        PaneLayer(rank = PaneLayerRank.CONTENT, modal = false) {
                            val state = rememberLazyListState().also { listState = it }
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag(PRESS_TARGET_TAG),
                                state = state,
                            ) {
                                items(List(100) { it }) {
                                    Box(modifier = Modifier.fillMaxWidth().height(40.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(TAG_PANE_HOVER_BARRIER).assertDoesNotExist()

        composeTestRule.onNodeWithTag(PRESS_TARGET_TAG).performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            val scrolled = listState!!.firstVisibleItemIndex > 0 ||
                listState!!.firstVisibleItemScrollOffset > 0
            scrolled shouldBe true
        }
        composeTestRule.onNodeWithTag(TAG_PANE_HOVER_BARRIER).assertDoesNotExist()
    }

    companion object {
        private const val PULSE_DURATION_MS = 420L
        private const val PULSE_COMPOSE_FRAMES = 4
        private const val CONTENT_TAG = "layer.content"
        private const val OVERLAY_TAG = "layer.overlay"
        private const val OTHER_PANE_FIELD_TAG = "pane.b.field"
        private const val PRESS_TARGET_TAG = "press.target"
        private const val PANE_A = "A"
        private const val PANE_B = "B"
        private const val MODAL_SURFACE_TAG = "modal.surface"
    }
}
