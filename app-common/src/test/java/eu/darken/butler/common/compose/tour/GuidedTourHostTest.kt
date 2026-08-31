package eu.darken.butler.common.compose.tour

import android.view.KeyEvent as NativeKeyEvent
import android.view.View
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Test
import testhelpers.ComposeTest

class GuidedTourHostTest : ComposeTest() {

    private val protectedDef = TourDefinition(
        id = TourId("test.protected"),
        steps = listOf(
            TourStep(stepId = "first", body = "Body of step 1".toCaString()),
            TourStep(stepId = "second", body = "Body of step 2".toCaString()),
        ),
        clickProtection = true,
    )

    private val unprotectedDef = protectedDef.copy(
        id = TourId("test.unprotected"),
        clickProtection = false,
    )

    private val centerlessDef = TourDefinition(
        id = TourId("test.centerless"),
        steps = listOf(
            TourStep(
                stepId = "overview",
                targetId = null,
                body = "Body of overview".toCaString(),
            ),
            TourStep(stepId = "second", body = "Body of step 2".toCaString()),
        ),
        clickProtection = true,
    )

    private val targetRect = Rect(left = 100f, top = 100f, right = 200f, bottom = 200f)

    @Test
    fun `idle host renders content`() {
        val sessionFlow = MutableStateFlow<TourSession?>(null)
        composeTestRule.setHostContent(sessionFlow) {
            Text("CONTENT_MARKER")
        }
        composeTestRule.onNodeWithText("CONTENT_MARKER").assertExists()
        composeTestRule.onAllNodesWithText("Body of step 1").assertCountEquals(0)
    }

    @Test
    fun `active session with missing target still renders content`() {
        val sessionFlow = MutableStateFlow<TourSession?>(TourSession(protectedDef, 0))
        composeTestRule.setHostContent(sessionFlow) {
            Text("CONTENT_MARKER")
        }
        composeTestRule.onNodeWithText("CONTENT_MARKER").assertExists()
    }

    @Test
    fun `bubble and Next button render when session active and target known`() {
        val sessionFlow = MutableStateFlow<TourSession?>(TourSession(protectedDef, 0))
        composeTestRule.setHostContent(sessionFlow, preregister = mapOf("first" to targetRect)) {
            Text("UNDER_SCRIM")
        }
        composeTestRule.onNodeWithText("Body of step 1").assertExists()
        composeTestRule.onNodeWithContentDescription("Next").assertExists()
    }

    @Test
    fun `mascot greets on the opening step and settles on later ones`() {
        val sessionFlow = MutableStateFlow<TourSession?>(TourSession(protectedDef, 0))
        composeTestRule.setHostContent(
            sessionFlow,
            preregister = mapOf("first" to targetRect, "second" to targetRect),
        ) {
            Text("CONTENT_MARKER")
        }
        composeTestRule.onNodeWithContentDescription("Butler mascot waving hello").assertExists()

        sessionFlow.value = TourSession(protectedDef, 1)
        composeTestRule.onNodeWithContentDescription("Butler mascot").assertExists()
        composeTestRule.onAllNodesWithContentDescription("Butler mascot waving hello").assertCountEquals(0)
    }

    @Test
    fun `Next click invokes onNext`() {
        val sessionFlow = MutableStateFlow<TourSession?>(TourSession(protectedDef, 0))
        var nextCount = 0
        composeTestRule.setHostContent(
            sessionFlow,
            preregister = mapOf("first" to targetRect),
            onNext = { _, _, _ -> nextCount++ },
        ) {
            Text("CONTENT_MARKER")
        }
        composeTestRule.onNodeWithContentDescription("Next").performClick()
        nextCount shouldBe 1
    }

    @Test
    fun `Skip icon opens confirm — neither callback fires yet`() {
        val sessionFlow = MutableStateFlow<TourSession?>(TourSession(protectedDef, 0))
        var dismissCount = 0
        var disableAllCount = 0
        composeTestRule.setHostContent(
            sessionFlow,
            preregister = mapOf("first" to targetRect),
            onDontShowAgain = { dismissCount++ },
            onDisableAllTours = { disableAllCount++ },
        ) {
            Text("CONTENT_MARKER")
        }
        // Pre-state: confirm not visible.
        composeTestRule.onAllNodesWithText("Skip the tour?").assertCountEquals(0)
        // Tap the X — confirm appears, no controller calls yet.
        composeTestRule.onNodeWithContentDescription("Skip").performClick()
        composeTestRule.onNodeWithText("Skip the tour?").assertExists()
        dismissCount shouldBe 0
        disableAllCount shouldBe 0
    }

    @Test
    fun `confirm Continue tour returns to step view without firing callbacks`() {
        val sessionFlow = MutableStateFlow<TourSession?>(TourSession(protectedDef, 0))
        var dismissCount = 0
        composeTestRule.setHostContent(
            sessionFlow,
            preregister = mapOf("first" to targetRect),
            onDontShowAgain = { dismissCount++ },
        ) {
            Text("CONTENT_MARKER")
        }
        composeTestRule.onNodeWithContentDescription("Skip").performClick()
        composeTestRule.onNodeWithText("Continue tour").performClick()
        composeTestRule.onNodeWithText("Body of step 1").assertExists()
        dismissCount shouldBe 0
    }

    @Test
    fun `confirm Don't show this tour invokes onDontShowAgain only`() {
        val sessionFlow = MutableStateFlow<TourSession?>(TourSession(protectedDef, 0))
        var dismissCount = 0
        var disableAllCount = 0
        composeTestRule.setHostContent(
            sessionFlow,
            preregister = mapOf("first" to targetRect),
            onDontShowAgain = { dismissCount++ },
            onDisableAllTours = { disableAllCount++ },
        ) {
            Text("CONTENT_MARKER")
        }
        composeTestRule.onNodeWithContentDescription("Skip").performClick()
        composeTestRule.onNodeWithText("Don't show this tour").performScrollTo().performClick()
        dismissCount shouldBe 1
        disableAllCount shouldBe 0
    }

    @Test
    fun `confirm Disable all tours invokes onDisableAllTours only`() {
        val sessionFlow = MutableStateFlow<TourSession?>(TourSession(protectedDef, 0))
        var dismissCount = 0
        var disableAllCount = 0
        composeTestRule.setHostContent(
            sessionFlow,
            preregister = mapOf("first" to targetRect),
            onDontShowAgain = { dismissCount++ },
            onDisableAllTours = { disableAllCount++ },
        ) {
            Text("CONTENT_MARKER")
        }
        composeTestRule.onNodeWithContentDescription("Skip").performClick()
        composeTestRule.onNodeWithText("Disable all tours").performScrollTo().performClick()
        disableAllCount shouldBe 1
        dismissCount shouldBe 0
    }

    @Test
    fun `clickProtection true blocks underlying clickable but Next still works`() {
        val sessionFlow = MutableStateFlow<TourSession?>(TourSession(protectedDef, 0))
        var underlyingClicks = 0
        var nextCount = 0
        composeTestRule.setHostContent(
            sessionFlow,
            preregister = mapOf("first" to targetRect),
            onNext = { _, _, _ -> nextCount++ },
        ) {
            // Small clickable at top-start. The bubble (placeBelow = true given the target rect)
            // is anchored top-center, padded down 16dp from targetRect.bottom (~216dp) — its
            // y-range never reaches 0..40, so UNDER's tap center sits outside the bubble.
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .align(Alignment.TopStart)
                        .testTag("UNDER")
                        .clickable { underlyingClicks++ },
                )
            }
        }
        composeTestRule.onNodeWithTag("UNDER").performClick()
        underlyingClicks shouldBe 0
        composeTestRule.onNodeWithContentDescription("Next").performClick()
        nextCount shouldBe 1
    }

    @Test
    fun `clickProtection false lets clicks reach underlying content via overlay-visible regions`() {
        val sessionFlow = MutableStateFlow<TourSession?>(TourSession(unprotectedDef, 0))
        var underlyingClicks = 0
        composeTestRule.setHostContent(
            sessionFlow,
            preregister = mapOf("first" to targetRect),
        ) {
            // Top-start clickable, see geometry note in the protected variant above.
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .align(Alignment.TopStart)
                        .testTag("UNDER")
                        .clickable { underlyingClicks++ },
                )
            }
        }
        composeTestRule.onNodeWithTag("UNDER").performClick()
        (underlyingClicks > 0) shouldBe true
    }

    @Test
    fun `onStepRendered reports the anchored step that became visible`() {
        val sessionFlow = MutableStateFlow<TourSession?>(TourSession(protectedDef, 0))
        val rendered = mutableListOf<Pair<TourId, String>>()
        composeTestRule.setHostContent(
            sessionFlow,
            preregister = mapOf("first" to targetRect),
            onStepRendered = { tourId, stepId -> rendered += tourId to stepId },
        ) {
            Text("CONTENT_MARKER")
        }
        rendered.distinct() shouldBe listOf(protectedDef.id to "first")
    }

    @Test
    fun `onStepRendered reports a centerless step`() {
        val sessionFlow = MutableStateFlow<TourSession?>(TourSession(centerlessDef, 0))
        val rendered = mutableListOf<Pair<TourId, String>>()
        composeTestRule.setHostContent(
            sessionFlow,
            onStepRendered = { tourId, stepId -> rendered += tourId to stepId },
        ) {
            Text("CONTENT_MARKER")
        }
        rendered.distinct() shouldBe listOf(centerlessDef.id to "overview")
    }

    @Test
    fun `onStepRendered does not fire while the target is still pending`() {
        // protectedDef step 0 target "first" is never registered → layout stays Pending.
        val sessionFlow = MutableStateFlow<TourSession?>(TourSession(protectedDef, 0))
        val rendered = mutableListOf<String>()
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setHostContent(sessionFlow, onStepRendered = { _, stepId -> rendered += stepId }) {
            Text("CONTENT_MARKER")
        }
        composeTestRule.mainClock.advanceTimeBy(100)
        composeTestRule.mainClock.autoAdvance = true
        rendered shouldBe emptyList()
    }

    @Test
    fun `a pending target auto-skips once the grace window elapses`() {
        // Nothing is drawn during the window and the step's anchor never registers, so this is the
        // one behaviour that has no observable on-device state — it lives here on purpose.
        val sessionFlow = MutableStateFlow<TourSession?>(TourSession(protectedDef, 0))
        val advances = mutableListOf<Pair<TourId, String>>()
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setHostContent(
            sessionFlow,
            onNext = { tourId, stepId, _ -> advances += tourId to stepId },
        ) {
            Text("CONTENT_MARKER")
        }
        composeTestRule.mainClock.advanceTimeBy(MISSING_TARGET_GRACE_MS / 2)
        advances shouldBe emptyList()
        composeTestRule.mainClock.advanceTimeBy(MISSING_TARGET_GRACE_MS)
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()
        // The skip names the step it was waiting on, so the controller accepts it.
        advances shouldBe listOf(protectedDef.id to "first")
    }

    @Test
    fun `two immediate Next taps report a single advance`() {
        // The controller drops a request naming a step it already left, but a double tap that spans
        // the recomposition would carry the NEW step's id, so the host debounces the tap itself.
        val sessionFlow = MutableStateFlow<TourSession?>(TourSession(protectedDef, 0))
        val advances = mutableListOf<Pair<TourId, String>>()
        composeTestRule.setHostContent(
            sessionFlow,
            preregister = mapOf("first" to targetRect),
            onNext = { tourId, stepId, _ -> advances += tourId to stepId },
        ) {
            Text("CONTENT_MARKER")
        }
        composeTestRule.onNodeWithContentDescription("Next").performClick()
        composeTestRule.onNodeWithContentDescription("Next").performClick()
        advances shouldBe listOf(protectedDef.id to "first")
    }

    @Test
    fun `centerless step renders body without any registered target`() {
        val sessionFlow = MutableStateFlow<TourSession?>(TourSession(centerlessDef, 0))
        composeTestRule.setHostContent(sessionFlow) {
            Text("CONTENT_MARKER")
        }
        composeTestRule.onNodeWithText("Body of overview").assertExists()
        composeTestRule.onNodeWithContentDescription("Next").assertExists()
    }

    @Test
    fun `centerless step does not auto-skip past the grace window`() {
        val sessionFlow = MutableStateFlow<TourSession?>(TourSession(centerlessDef, 0))
        var nextCount = 0
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setHostContent(sessionFlow, onNext = { _, _, _ -> nextCount++ }) {
            Text("CONTENT_MARKER")
        }
        // Advance well past MISSING_TARGET_GRACE_MS — if grace-skip leaked, onNext would fire.
        composeTestRule.mainClock.advanceTimeBy(MISSING_TARGET_GRACE_MS * 4)
        composeTestRule.mainClock.autoAdvance = true
        nextCount shouldBe 0
        composeTestRule.onNodeWithText("Body of overview").assertExists()
    }

    @Test
    fun `clickProtection blocks underlying clicks during centerless step`() {
        val sessionFlow = MutableStateFlow<TourSession?>(TourSession(centerlessDef, 0))
        var underlyingClicks = 0
        var nextCount = 0
        composeTestRule.setHostContent(
            sessionFlow,
            onNext = { _, _, _ -> nextCount++ },
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .align(Alignment.TopStart)
                        .testTag("UNDER")
                        .clickable { underlyingClicks++ },
                )
            }
        }
        composeTestRule.onNodeWithTag("UNDER").performClick()
        underlyingClicks shouldBe 0
        composeTestRule.onNodeWithContentDescription("Next").performClick()
        nextCount shouldBe 1
    }

    @Test
    fun `D-pad focus is pulled into the bubble when a step is shown`() {
        val sessionFlow = MutableStateFlow<TourSession?>(TourSession(protectedDef, 0))
        composeTestRule.setHostContent(sessionFlow, preregister = mapOf("first" to targetRect)) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .align(Alignment.TopStart)
                        .testTag("UNDER")
                        .clickable {},
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Next").assertIsFocused()
    }

    @Test
    fun `DPAD_CENTER activates Next, never the background clickable`() {
        val sessionFlow = MutableStateFlow<TourSession?>(TourSession(protectedDef, 0))
        var underlyingClicks = 0
        var nextCount = 0
        composeTestRule.setHostContent(
            sessionFlow,
            preregister = mapOf("first" to targetRect),
            onNext = { _, _, _ -> nextCount++ },
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .align(Alignment.TopStart)
                        .testTag("UNDER")
                        .clickable { underlyingClicks++ },
                )
            }
        }
        composeTestRule.pressKey(NativeKeyEvent.KEYCODE_DPAD_CENTER)
        nextCount shouldBe 1
        underlyingClicks shouldBe 0
    }

    @Test
    fun `focus stays trapped in the bubble across D-pad navigation`() {
        val sessionFlow = MutableStateFlow<TourSession?>(TourSession(protectedDef, 0))
        var underlyingClicks = 0
        composeTestRule.setHostContent(sessionFlow, preregister = mapOf("first" to targetRect)) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .align(Alignment.TopStart)
                        .testTag("UNDER")
                        .clickable { underlyingClicks++ },
                )
            }
        }
        // Try hard to escape toward the background clickable in the top-start corner.
        repeat(3) { composeTestRule.pressKey(NativeKeyEvent.KEYCODE_DPAD_UP) }
        repeat(3) { composeTestRule.pressKey(NativeKeyEvent.KEYCODE_DPAD_LEFT) }
        composeTestRule.pressKey(NativeKeyEvent.KEYCODE_DPAD_CENTER)
        underlyingClicks shouldBe 0
    }

    @Test
    fun `confirm view re-anchors focus on Continue tour`() {
        val sessionFlow = MutableStateFlow<TourSession?>(TourSession(protectedDef, 0))
        composeTestRule.setHostContent(sessionFlow, preregister = mapOf("first" to targetRect)) {
            Text("CONTENT_MARKER")
        }
        composeTestRule.onNodeWithContentDescription("Skip").performClick()
        composeTestRule.onNodeWithText("Continue tour").assertIsFocused()
    }

    @Test
    fun `D-pad keys are shielded during the pending-target grace window`() {
        // Step has a targetId that is never registered: layout stays Pending, no bubble exists.
        val sessionFlow = MutableStateFlow<TourSession?>(TourSession(protectedDef, 0))
        var underlyingClicks = 0
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setHostContent(sessionFlow) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .align(Alignment.TopStart)
                        .testTag("UNDER")
                        .clickable { underlyingClicks++ },
                )
            }
        }
        composeTestRule.mainClock.advanceTimeBy(100)
        // Background must not be reachable/activatable while the target is unresolved.
        composeTestRule.pressKey(NativeKeyEvent.KEYCODE_DPAD_DOWN)
        composeTestRule.pressKey(NativeKeyEvent.KEYCODE_DPAD_CENTER)
        composeTestRule.mainClock.autoAdvance = true
        underlyingClicks shouldBe 0
    }

    @Test
    fun `keyboard keys are consumed during the pending-target grace window`() {
        // A hardware keyboard is not covered by clickProtection: without the shield, Tab traverses
        // focus into the content, Space activates it and characters type into it.
        val sessionFlow = MutableStateFlow<TourSession?>(TourSession(protectedDef, 0))
        val received = mutableListOf<Key>()
        composeTestRule.setHostContent(sessionFlow) { KeyProbe(received) }
        composeTestRule.pressKey(NativeKeyEvent.KEYCODE_TAB)
        composeTestRule.pressKey(NativeKeyEvent.KEYCODE_SPACE)
        composeTestRule.pressKey(NativeKeyEvent.KEYCODE_A)
        received shouldBe emptyList()
    }

    @Test
    fun `volume keys reach the content through the shield`() {
        // Consuming these would leave volume control dead for as long as a tour is up.
        val sessionFlow = MutableStateFlow<TourSession?>(TourSession(protectedDef, 0))
        val received = mutableListOf<Key>()
        composeTestRule.setHostContent(sessionFlow) { KeyProbe(received) }
        composeTestRule.pressKey(NativeKeyEvent.KEYCODE_VOLUME_UP)
        composeTestRule.pressKey(NativeKeyEvent.KEYCODE_VOLUME_DOWN)
        received.contains(Key.VolumeUp) shouldBe true
        received.contains(Key.VolumeDown) shouldBe true
    }

    // Back presses are dispatched through the REAL OnBackPressedDispatcherOwner captured from
    // inside the composition (the test activity). A custom LocalOnBackPressedDispatcherOwner
    // was unreliable under Robolectric; the real owner dispatches fine.

    @Test
    fun `back at a later step invokes onPrevious, never a terminal action`() {
        val sessionFlow = MutableStateFlow<TourSession?>(TourSession(protectedDef, 1))
        var previousCount = 0
        var dismissCount = 0
        var backOwner: OnBackPressedDispatcherOwner? = null
        composeTestRule.setHostContent(
            sessionFlow,
            preregister = mapOf("second" to targetRect),
            onPrevious = { previousCount++ },
            onDontShowAgain = { dismissCount++ },
            onBackOwner = { backOwner = it },
        ) {
            Text("CONTENT_MARKER")
        }
        composeTestRule.pressBack(backOwner)
        previousCount shouldBe 1
        dismissCount shouldBe 0
        composeTestRule.onAllNodesWithText("Skip the tour?").assertCountEquals(0)
    }

    @Test
    fun `back at the first step opens the exit confirm without firing callbacks`() {
        val sessionFlow = MutableStateFlow<TourSession?>(TourSession(protectedDef, 0))
        var previousCount = 0
        var dismissCount = 0
        var backOwner: OnBackPressedDispatcherOwner? = null
        composeTestRule.setHostContent(
            sessionFlow,
            preregister = mapOf("first" to targetRect),
            onPrevious = { previousCount++ },
            onDontShowAgain = { dismissCount++ },
            onBackOwner = { backOwner = it },
        ) {
            Text("CONTENT_MARKER")
        }
        composeTestRule.pressBack(backOwner)
        composeTestRule.onNodeWithText("Skip the tour?").assertExists()
        composeTestRule.onNodeWithText("Continue tour").assertIsFocused()
        previousCount shouldBe 0
        dismissCount shouldBe 0
    }

    @Test
    fun `back while the confirm is showing returns to the step view and refocuses Next`() {
        val sessionFlow = MutableStateFlow<TourSession?>(TourSession(protectedDef, 0))
        var dismissCount = 0
        var backOwner: OnBackPressedDispatcherOwner? = null
        composeTestRule.setHostContent(
            sessionFlow,
            preregister = mapOf("first" to targetRect),
            onDontShowAgain = { dismissCount++ },
            onBackOwner = { backOwner = it },
        ) {
            Text("CONTENT_MARKER")
        }
        composeTestRule.onNodeWithContentDescription("Skip").performClick()
        composeTestRule.onNodeWithText("Skip the tour?").assertExists()
        composeTestRule.pressBack(backOwner)
        composeTestRule.onAllNodesWithText("Skip the tour?").assertCountEquals(0)
        composeTestRule.onNodeWithText("Body of step 1").assertExists()
        composeTestRule.onNodeWithContentDescription("Next").assertIsFocused()
        dismissCount shouldBe 0
    }

    @Test
    fun `tour BackHandler wins LIFO dispatch over a back handler in the wrapped content`() {
        val sessionFlow = MutableStateFlow<TourSession?>(TourSession(protectedDef, 0))
        var underlyingBacks = 0
        var backOwner: OnBackPressedDispatcherOwner? = null
        composeTestRule.setHostContent(
            sessionFlow,
            preregister = mapOf("first" to targetRect),
            onBackOwner = { backOwner = it },
        ) {
            BackHandler { underlyingBacks++ }
            Text("CONTENT_MARKER")
        }
        composeTestRule.pressBack(backOwner)
        composeTestRule.onNodeWithText("Skip the tour?").assertExists()
        underlyingBacks shouldBe 0
    }

    @Test
    fun `back during the pending grace window is consumed, not passed to content`() {
        // Step 0 with an unregistered target: layout stays Pending, no bubble exists. Back must
        // neither cancel the tour nor reach back handlers beneath the host.
        val sessionFlow = MutableStateFlow<TourSession?>(TourSession(protectedDef, 0))
        var underlyingBacks = 0
        var dismissCount = 0
        var backOwner: OnBackPressedDispatcherOwner? = null
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setHostContent(
            sessionFlow,
            onDontShowAgain = { dismissCount++ },
            onBackOwner = { backOwner = it },
        ) {
            BackHandler { underlyingBacks++ }
            Text("CONTENT_MARKER")
        }
        composeTestRule.mainClock.advanceTimeBy(100)
        composeTestRule.pressBack(backOwner)
        composeTestRule.mainClock.autoAdvance = true
        underlyingBacks shouldBe 0
        dismissCount shouldBe 0
    }

    @Test
    fun `step change while the confirm is showing resets to the step view`() {
        val sessionFlow = MutableStateFlow<TourSession?>(TourSession(protectedDef, 0))
        composeTestRule.setHostContent(
            sessionFlow,
            preregister = mapOf("first" to targetRect, "second" to targetRect),
        ) {
            Text("CONTENT_MARKER")
        }
        composeTestRule.onNodeWithContentDescription("Skip").performClick()
        composeTestRule.onNodeWithText("Skip the tour?").assertExists()
        sessionFlow.value = TourSession(protectedDef, 1)
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithText("Skip the tour?").assertCountEquals(0)
        composeTestRule.onNodeWithText("Body of step 2").assertExists()
    }

    @Test
    fun `anchored bubble keeps its copy when the anchor leaves almost no room`() {
        val def = TourDefinition(
            id = TourId("test.cramped"),
            steps = listOf(
                TourStep(
                    stepId = "only",
                    title = "Cramped title".toCaString(),
                    body = "Cramped body".toCaString(),
                ),
            ),
        )
        val anchor = with(composeTestRule.density) {
            Rect(left = 0f, top = 20.dp.toPx(), right = 400.dp.toPx(), bottom = 180.dp.toPx())
        }
        composeTestRule.setHostContent(
            session = MutableStateFlow(TourSession(def, stepIndex = 0)),
            hostModifier = Modifier.size(width = 400.dp, height = 240.dp),
            preregister = mapOf("only" to anchor),
        ) { Box(Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithText("Cramped title").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cramped body").assertIsDisplayed()
    }

    @Test
    fun `bubble placed above the anchor also keeps its copy`() {
        // Anchor near the bottom edge, so the bubble takes the above-the-target branch.
        val def = TourDefinition(
            id = TourId("test.cramped.above"),
            steps = listOf(
                TourStep(
                    stepId = "only",
                    title = "Cramped title".toCaString(),
                    body = "Cramped body".toCaString(),
                ),
            ),
        )
        val anchor = with(composeTestRule.density) {
            Rect(left = 0f, top = 200.dp.toPx(), right = 400.dp.toPx(), bottom = 230.dp.toPx())
        }
        composeTestRule.setHostContent(
            session = MutableStateFlow(TourSession(def, stepIndex = 0)),
            hostModifier = Modifier.size(width = 400.dp, height = 240.dp),
            preregister = mapOf("only" to anchor),
        ) { Box(Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithText("Cramped title").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cramped body").assertIsDisplayed()
    }

    @Test
    fun `non-zero insets do not push the bubble under the safe area`() {
        val def = TourDefinition(
            id = TourId("test.cramped.insets"),
            steps = listOf(
                TourStep(
                    stepId = "only",
                    title = "Cramped title".toCaString(),
                    body = "Cramped body".toCaString(),
                ),
            ),
        )
        val anchor = with(composeTestRule.density) {
            Rect(left = 0f, top = 20.dp.toPx(), right = 400.dp.toPx(), bottom = 180.dp.toPx())
        }
        var view: View? = null
        composeTestRule.setHostContent(
            session = MutableStateFlow(TourSession(def, stepIndex = 0)),
            hostModifier = Modifier.size(width = 400.dp, height = 240.dp),
            preregister = mapOf("only" to anchor),
            onView = { view = it },
        ) { Box(Modifier.fillMaxSize()) }
        composeTestRule.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 0, 0, BOTTOM_INSET_PX))
                .build()
            ViewCompat.dispatchApplyWindowInsets(view!!, insets)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Cramped title").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cramped body").assertIsDisplayed()
        // Room for the copy comes out of the target-side gap, never out of the safe area: the
        // controls stay above the bottom inset.
        // Single-step tour, so the advance button is the finishing one.
        val controlsBottom = composeTestRule.onNodeWithContentDescription("Done")
            .getUnclippedBoundsInRoot()
            .bottom
        (controlsBottom <= 240.dp - BOTTOM_INSET_PX.dp) shouldBe true
    }

    @Test
    fun `the floor grows with font scale`() {
        val def = TourDefinition(
            id = TourId("test.cramped.fontscale"),
            steps = listOf(
                TourStep(
                    stepId = "only",
                    title = "Cramped title".toCaString(),
                    body = "Cramped body".toCaString(),
                ),
            ),
        )
        val anchor = with(composeTestRule.density) {
            Rect(left = 0f, top = 20.dp.toPx(), right = 400.dp.toPx(), bottom = 180.dp.toPx())
        }
        composeTestRule.setHostContent(
            session = MutableStateFlow(TourSession(def, stepIndex = 0)),
            hostModifier = Modifier.size(width = 400.dp, height = 240.dp),
            preregister = mapOf("only" to anchor),
            // A plain Density converts sp to dp linearly, so the reserved copy height doubles
            // here — unlike text measurement, which Robolectric fakes at a fixed line height.
            // That linearity is also this test's limit: from API 34 the platform damps large sp
            // values instead, and a floor that fails to grow under that curve still passes here.
            // This covers the arithmetic; only an on-device check covers the curve.
            densityOverride = Density(density = 1f, fontScale = 2f),
        ) { Box(Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithText("Cramped title").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cramped body").assertIsDisplayed()
    }

    @Test
    fun `centerless bubble keeps its copy on a short screen`() {
        val def = TourDefinition(
            id = TourId("test.centerless.cramped"),
            steps = listOf(
                TourStep(
                    stepId = "only",
                    targetId = null,
                    title = "Cramped title".toCaString(),
                    body = "Cramped body".toCaString(),
                ),
            ),
        )
        composeTestRule.setHostContent(
            session = MutableStateFlow(TourSession(def, stepIndex = 0)),
            hostModifier = Modifier.size(width = 400.dp, height = 140.dp),
        ) { Box(Modifier.fillMaxSize()) }

        composeTestRule.onNodeWithText("Cramped title").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cramped body").assertIsDisplayed()
    }
}

/** 40dp at the tests' density (1f), applied as a bottom system-bar inset. */
private const val BOTTOM_INSET_PX = 40

/**
 * Focused content that records every key event reaching it. Key events travel the focused node's
 * path, so anything the host's root shield consumes never lands here.
 */
@Composable
private fun KeyProbe(received: MutableList<Key>) {
    val focusRequester = remember { FocusRequester() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                received += event.key
                false
            },
    )
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

private fun ComposeContentTestRule.setHostContent(
    session: StateFlow<TourSession?>,
    preregister: Map<String, Rect> = emptyMap(),
    onNext: (TourId, String, TourDefinition) -> Unit = { _, _, _ -> },
    onPrevious: () -> Unit = {},
    onDontShowAgain: () -> Unit = {},
    onDisableAllTours: () -> Unit = {},
    onStepRendered: (TourId, String) -> Unit = { _, _ -> },
    onBackOwner: (OnBackPressedDispatcherOwner) -> Unit = {},
    onView: (View) -> Unit = {},
    hostModifier: Modifier = Modifier.fillMaxSize(),
    densityOverride: Density? = null,
    content: @Composable () -> Unit,
) {
    // Pre-seed a registry the host will use directly (skips real layout).
    val registry = TourTargetRegistry()
    preregister.forEach { (id, rect) -> registry.put(id, rect, owner = id) }
    setContent {
        LocalOnBackPressedDispatcherOwner.current?.let { owner ->
            SideEffect { onBackOwner(owner) }
        }
        val view = LocalView.current
        SideEffect { onView(view) }
        PreviewWrapper {
            CompositionLocalProvider(LocalDensity provides (densityOverride ?: LocalDensity.current)) {
                GuidedTourHost(
                    session = session,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    onDontShowAgain = onDontShowAgain,
                    onDisableAllTours = onDisableAllTours,
                    modifier = hostModifier,
                    registry = registry,
                    onStepRendered = onStepRendered,
                    content = content,
                )
            }
        }
    }
}

private fun ComposeContentTestRule.pressBack(owner: OnBackPressedDispatcherOwner?) {
    val o = owner ?: error("no OnBackPressedDispatcherOwner captured from the composition")
    runOnUiThread { o.onBackPressedDispatcher.onBackPressed() }
    waitForIdle()
}

private fun ComposeContentTestRule.pressKey(keyCode: Int) {
    onRoot().performKeyPress(ComposeKeyEvent(NativeKeyEvent(NativeKeyEvent.ACTION_DOWN, keyCode)))
    onRoot().performKeyPress(ComposeKeyEvent(NativeKeyEvent(NativeKeyEvent.ACTION_UP, keyCode)))
}
