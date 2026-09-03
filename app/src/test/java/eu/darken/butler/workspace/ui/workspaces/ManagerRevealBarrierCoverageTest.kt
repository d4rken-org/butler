package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.maxRevealRadius
import eu.darken.butler.workspace.ui.manager.WorkspaceRevealOrigin
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.math.hypot

private const val BENEATH_TAG = "reveal.beneath"

/**
 * The overlay covers the whole screen from the first frame, but the reveal only uncovers a circle
 * around the button that opened it. The screen behind is still there, still hit-testable, and a tap
 * on the part the circle has not reached yet must not reach it.
 */
class ManagerRevealBarrierCoverageTest : ComposeTest() {

    private fun ComposeContentTestRule.applyAndAdvanceBy(millis: Long) {
        waitForIdle()
        mainClock.advanceTimeBy(millis)
    }

    @Test
    fun `a tap outside the growing circle does not reach the screen behind`() {
        var visible by mutableStateOf(false)
        var beneathClicks = 0
        var state: ManagerRevealState? = null
        val origin = WorkspaceRevealOrigin()

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            PreviewWrapper {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(BENEATH_TAG)
                            .clickable { beneathClicks++ },
                    )
                    val revealState = rememberManagerRevealState(visible = visible)
                    state = revealState
                    ManagerRevealOverlay(state = revealState, revealOrigin = origin) {
                        Box(modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }

        composeTestRule.applyAndAdvanceBy(1_000)

        val bounds = composeTestRule.onNodeWithTag(BENEATH_TAG).fetchSemanticsNode().boundsInRoot
        val size = Size(bounds.width, bounds.height)
        // Aim the reveal at the top left corner, so the opposite corner is the point that stays
        // outside the circle for the longest.
        composeTestRule.runOnIdle { origin.offset = Offset(bounds.left, bounds.top) }

        composeTestRule.runOnIdle { visible = true }
        composeTestRule.applyAndAdvanceBy(50)

        val progress = composeTestRule.runOnIdle { state!!.progress.value }
        val radius = maxRevealRadius(Offset.Zero, size) * progress
        // Node-local, and the node fills the same area as the overlay, so this is also the distance
        // from the circle's centre.
        val farCorner = Offset(size.width * 0.97f, size.height * 0.97f)
        val distance = hypot(farCorner.x, farCorner.y)

        withClue("the reveal has to be part-way through, otherwise nothing is being covered up") {
            progress shouldBeGreaterThan 0f
            progress shouldBeLessThan 1f
        }
        withClue("the tap has to land outside the circle for this to test anything") {
            distance shouldBeGreaterThan radius
        }

        composeTestRule.onNodeWithTag(BENEATH_TAG).performTouchInput { click(farCorner) }
        composeTestRule.waitForIdle()

        withClue(
            "A tap on the part of the screen the reveal has not uncovered yet went through the " +
                "tab manager and hit the workspace behind it. The reveal was $progress of the way " +
                "in, so the circle had a radius of $radius and the tap landed $distance out."
        ) {
            beneathClicks shouldBe 0
        }

        // Guards the assertion above against passing for the wrong reason: once the reveal settles
        // and the barrier goes away, the very same coordinate does reach the node underneath.
        composeTestRule.applyAndAdvanceBy(1_000)
        composeTestRule.onNodeWithTag(BENEATH_TAG).performTouchInput { click(farCorner) }
        composeTestRule.waitForIdle()
        withClue("the tap has to be able to reach the node underneath at all") {
            beneathClicks shouldBe 1
        }
    }
}
