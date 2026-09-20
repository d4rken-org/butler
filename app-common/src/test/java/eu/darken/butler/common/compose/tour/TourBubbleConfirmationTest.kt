package eu.darken.butler.common.compose.tour

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import testhelpers.ComposeTest

/**
 * Bounds coverage for the exit-confirm placement: the confirm always takes the centerless
 * safe-area-bounded placement (no tail), even for anchored steps, so its three stacked actions can
 * never be clipped by the target-relative height cap a normal step uses.
 *
 * The bounds tests run under [GraphicsMode.Mode.NATIVE] because they need real text metrics — under
 * Robolectric's default mode every style measures at the same fake line height and the confirm
 * column would never overflow. They render [TourBubble] directly with `initialConfirm = true`, so
 * StepContent (and its Lottie mascot) is never composed. Interaction flows stay in the default
 * graphics mode.
 */
class TourBubbleConfirmationTest : ComposeTest() {

    private val def = TourDefinition(
        id = TourId("test.confirm"),
        steps = listOf(
            TourStep(stepId = "first", body = "Body of step 1".toCaString()),
            TourStep(stepId = "second", body = "Body of step 2".toCaString()),
        ),
    )

    private val centerlessDef = TourDefinition(
        id = TourId("test.confirm.centerless"),
        steps = listOf(
            TourStep(
                stepId = "overview",
                targetId = null,
                body = "Body of overview".toCaString(),
            ),
        ),
    )

    @Test
    @Config(qualifiers = "w1000dp-h600dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `anchored confirm on the below branch keeps all actions inside the safe area`() {
        // Target y=100..460 of 600: a normal step picks the below branch and caps the bubble at the
        // ~124dp under the target — pre-fix that clipped "Disable all tours" fully and "Don't show
        // this tour" partly (clipped != unclipped bounds). The confirm now centers in the safe area.
        val target = with(composeTestRule.density) {
            Rect(left = 200.dp.toPx(), top = 100.dp.toPx(), right = 800.dp.toPx(), bottom = 460.dp.toPx())
        }
        composeTestRule.setBubbleContent(
            session = TourSession(def, 0),
            layout = StepLayout.Anchored(target),
            hostModifier = Modifier.size(width = 1000.dp, height = 600.dp),
            initialConfirm = true,
        )
        assertRootSize(width = 1000.dp, height = 600.dp)
        assertConfirmFullyVisible(safeTop = 0.dp, safeBottom = 600.dp)
    }

    @Test
    @Config(qualifiers = "w1000dp-h600dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `anchored confirm on the above branch keeps all actions inside the safe area`() {
        // Mirror of the below-branch rect: y=140..500 leaves ~124dp above the target.
        val target = with(composeTestRule.density) {
            Rect(left = 200.dp.toPx(), top = 140.dp.toPx(), right = 800.dp.toPx(), bottom = 500.dp.toPx())
        }
        composeTestRule.setBubbleContent(
            session = TourSession(def, 0),
            layout = StepLayout.Anchored(target),
            hostModifier = Modifier.size(width = 1000.dp, height = 600.dp),
            initialConfirm = true,
        )
        assertRootSize(width = 1000.dp, height = 600.dp)
        assertConfirmFullyVisible(safeTop = 0.dp, safeBottom = 600.dp)
    }

    @Test
    @Config(qualifiers = "w1000dp-h600dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `centerless confirm keeps all actions inside the safe area`() {
        composeTestRule.setBubbleContent(
            session = TourSession(centerlessDef, 0),
            layout = StepLayout.Centerless,
            hostModifier = Modifier.size(width = 1000.dp, height = 600.dp),
            initialConfirm = true,
        )
        assertRootSize(width = 1000.dp, height = 600.dp)
        assertConfirmFullyVisible(safeTop = 0.dp, safeBottom = 600.dp)
    }

    @Test
    @Config(qualifiers = "w800dp-h1000dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `anchored confirm keeps all actions inside the safe area on a tall tablet`() {
        // Regression-only: this portrait geometry already fit the confirm pre-fix.
        val target = with(composeTestRule.density) {
            Rect(left = 100.dp.toPx(), top = 400.dp.toPx(), right = 700.dp.toPx(), bottom = 600.dp.toPx())
        }
        composeTestRule.setBubbleContent(
            session = TourSession(def, 0),
            layout = StepLayout.Anchored(target),
            hostModifier = Modifier.size(width = 800.dp, height = 1000.dp),
            initialConfirm = true,
        )
        assertRootSize(width = 800.dp, height = 1000.dp)
        assertConfirmFullyVisible(safeTop = 0.dp, safeBottom = 1000.dp)
    }

    @Test
    @Config(qualifiers = "w1000dp-h600dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `anchored confirm stays above a dispatched bottom inset`() {
        val target = with(composeTestRule.density) {
            Rect(left = 200.dp.toPx(), top = 100.dp.toPx(), right = 800.dp.toPx(), bottom = 460.dp.toPx())
        }
        var view: View? = null
        composeTestRule.setBubbleContent(
            session = TourSession(def, 0),
            layout = StepLayout.Anchored(target),
            hostModifier = Modifier.size(width = 1000.dp, height = 600.dp),
            initialConfirm = true,
            onView = { view = it },
        )
        composeTestRule.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 0, 0, BOTTOM_INSET_PX))
                .build()
            ViewCompat.dispatchApplyWindowInsets(view!!, insets)
        }
        composeTestRule.waitForIdle()

        assertConfirmFullyVisible(safeTop = 0.dp, safeBottom = 600.dp - BOTTOM_INSET_PX.dp)
    }

    @Test
    @Config(qualifiers = "w400dp-h240dp")
    fun `confirm actions stay reachable by scroll at large font scale and fire their callbacks`() {
        // A plain Density converts sp to dp linearly, so the confirm's text doubles here — unlike
        // text measurement, which Robolectric fakes at a fixed line height. That linearity is also
        // this test's limit: from API 34 the platform damps large sp values instead. This covers
        // the scroll/callback wiring; only an on-device check covers the curve.
        val target = with(composeTestRule.density) {
            Rect(left = 100.dp.toPx(), top = 60.dp.toPx(), right = 300.dp.toPx(), bottom = 180.dp.toPx())
        }
        var dismissCount = 0
        var disableAllCount = 0
        composeTestRule.setBubbleContent(
            session = TourSession(def, 0),
            layout = StepLayout.Anchored(target),
            hostModifier = Modifier.size(width = 400.dp, height = 240.dp),
            densityOverride = Density(density = 1f, fontScale = 2f),
            onDontShowAgain = { dismissCount++ },
            onDisableAllTours = { disableAllCount++ },
        )
        assertRootSize(width = 400.dp, height = 240.dp)

        composeTestRule.onNodeWithContentDescription("Skip").performClick()
        composeTestRule.onNodeWithText("Skip the tour?").assertIsDisplayed()

        // The column is taller than the bubble at this geometry: prove the scrolls below are
        // actually required — the confirm's scroll container has a non-zero range, starts at the
        // top, and the last action begins clipped off the bubble.
        val scrollRange = confirmScrollRange()
        withClue("scroll value=${scrollRange.value()} max=${scrollRange.maxValue()}") {
            (scrollRange.maxValue() > 0f) shouldBe true
            scrollRange.value() shouldBe 0f
        }
        val lastAction = composeTestRule.onNodeWithText("Disable all tours")
        withClue("clipped=${lastAction.getBoundsInRoot()} unclipped=${lastAction.getUnclippedBoundsInRoot()}") {
            (lastAction.getBoundsInRoot() != lastAction.getUnclippedBoundsInRoot()) shouldBe true
        }

        // The stacked actions are reached by scrolling, and each still routes to its own callback.
        lastAction.performScrollTo().performClick()
        disableAllCount shouldBe 1
        dismissCount shouldBe 0
        (confirmScrollRange().value() > 0f) shouldBe true
        composeTestRule.onNodeWithText("Don't show this tour").performScrollTo().performClick()
        dismissCount shouldBe 1

        composeTestRule.onNodeWithText("Continue tour").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Body of step 1").assertIsDisplayed()

        // Re-entering the confirm starts at the top again: its scroll state is per-confirm, not
        // carried over from the previous visit.
        composeTestRule.onNodeWithContentDescription("Skip").performClick()
        composeTestRule.onNodeWithText("Skip the tour?").assertIsDisplayed()
        val reentryRange = confirmScrollRange()
        withClue("re-entry scroll value=${reentryRange.value()} max=${reentryRange.maxValue()}") {
            (reentryRange.maxValue() > 0f) shouldBe true
            reentryRange.value() shouldBe 0f
        }
    }

    private fun confirmScrollRange(): ScrollAxisRange =
        composeTestRule.onNode(
            hasScrollAction() and hasAnyDescendant(hasText("Skip the tour?")),
        ).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]

    private fun assertRootSize(width: Dp, height: Dp) {
        val bounds = composeTestRule.onRoot().getUnclippedBoundsInRoot()
        withClue("root bounds=$bounds, expected ${width}x$height") {
            (bounds.right - bounds.left) shouldBe width
            (bounds.bottom - bounds.top) shouldBe height
        }
    }

    private fun assertConfirmFullyVisible(safeTop: Dp, safeBottom: Dp) {
        listOf(
            "Skip the tour?",
            "Continue tour",
            "Don't show this tour",
            "Disable all tours",
        ).forEach { text ->
            composeTestRule.onNodeWithText(text).assertFullyVisibleWithin(safeTop, safeBottom)
        }
    }

    private fun SemanticsNodeInteraction.assertFullyVisibleWithin(safeTop: Dp, safeBottom: Dp) {
        val unclipped = getUnclippedBoundsInRoot()
        val clipped = getBoundsInRoot()
        withClue("unclipped=$unclipped clipped=$clipped safe=[$safeTop, $safeBottom]") {
            clipped shouldBe unclipped
            (unclipped.top >= safeTop) shouldBe true
            (unclipped.bottom <= safeBottom) shouldBe true
        }
    }

    private fun ComposeContentTestRule.setBubbleContent(
        session: TourSession,
        layout: StepLayout,
        hostModifier: Modifier = Modifier.fillMaxSize(),
        densityOverride: Density? = null,
        initialConfirm: Boolean = false,
        onView: (View) -> Unit = {},
        onNext: () -> Unit = {},
        onPrevious: () -> Unit = {},
        onDontShowAgain: () -> Unit = {},
        onDisableAllTours: () -> Unit = {},
    ) {
        setContent {
            val view = LocalView.current
            SideEffect { onView(view) }
            PreviewWrapper {
                CompositionLocalProvider(LocalDensity provides (densityOverride ?: LocalDensity.current)) {
                    var showConfirm by remember { mutableStateOf(initialConfirm) }
                    Box(modifier = hostModifier) {
                        TourBubble(
                            step = session.currentStep,
                            layout = layout,
                            session = session,
                            showConfirm = showConfirm,
                            onShowConfirmChange = { showConfirm = it },
                            onNext = onNext,
                            onPrevious = onPrevious,
                            onDontShowAgain = onDontShowAgain,
                            onDisableAllTours = onDisableAllTours,
                        )
                    }
                }
            }
        }
    }
}

/** 40dp at the tests' density (1f), applied as a bottom system-bar inset. */
private const val BOTTOM_INSET_PX = 40
