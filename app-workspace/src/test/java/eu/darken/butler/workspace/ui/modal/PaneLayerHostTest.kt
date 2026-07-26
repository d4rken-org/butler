package eu.darken.butler.workspace.ui.modal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.LocalWorkspaceFocusRequest
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Test
import testhelpers.ComposeTest

class PaneLayerHostTest : ComposeTest() {

    private val hiddenFromAccessibility =
        SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility)

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
     * The pressed target must also end up holding focus. It asks for it on the *up* event, long
     * before the pane-focus request has travelled through the workspace plumbing and come back, so
     * nothing may be replayed on its behalf here — the press has to be enough on its own.
     */
    @Test
    fun `pressing another pane hands the focused pane over and gives the press focus`() {
        var focusedPane by mutableStateOf(PANE_A)
        var modalHasFocus = false
        var otherFieldHasFocus = false
        val modalFocus = FocusRequester()
        val otherFieldFocus = FocusRequester()
        var scope: CoroutineScope? = null

        composeTestRule.setContent {
            scope = rememberCoroutineScope()
            PreviewWrapper {
                Row {
                    CompositionLocalProvider(
                        LocalWorkspaceFocusRequest provides {
                            scope?.launch {
                                delay(PANE_FOCUS_ROUND_TRIP)
                                focusedPane = PANE_A
                            }
                        },
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
                        // Answered a round trip later, never synchronously from the press
                        LocalWorkspaceFocusRequest provides {
                            scope?.launch {
                                delay(PANE_FOCUS_ROUND_TRIP)
                                focusedPane = PANE_B
                            }
                        },
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
                                        .clickable { otherFieldFocus.requestFocus() },
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
        composeTestRule.mainClock.advanceTimeBy(500)
        composeTestRule.waitForIdle()

        composeTestRule.runOnIdle {
            // The press handed the focused pane over...
            focusedPane shouldBe PANE_B
            // ...the modal gave up its focus...
            modalHasFocus shouldBe false
            // ...and the thing that was actually pressed is holding it
            otherFieldHasFocus shouldBe true
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

    companion object {
        private const val CONTENT_TAG = "layer.content"
        private const val OVERLAY_TAG = "layer.overlay"
        private const val OTHER_PANE_FIELD_TAG = "pane.b.field"
        private const val PANE_A = "A"
        private const val PANE_B = "B"
        private const val PANE_FOCUS_ROUND_TRIP = 50L
        private const val MODAL_SURFACE_TAG = "modal.surface"
    }
}
