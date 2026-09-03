package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.manager.WorkspaceRevealOrigin
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

private const val CONTENT_TAG = "reveal.content"

@Composable
private fun RevealHarness(
    visible: Boolean,
    onState: (ManagerRevealState) -> Unit = {},
    onContentClick: () -> Unit = {},
) {
    val state = rememberManagerRevealState(visible = visible)
    onState(state)
    ManagerRevealOverlay(
        state = state,
        revealOrigin = remember { WorkspaceRevealOrigin() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(CONTENT_TAG)
                .clickable(onClick = onContentClick),
        )
    }
}

/**
 * With `autoAdvance = false` nothing applies the global snapshot between a `runOnIdle` write and the
 * next frame, so the recomposer would spend those frames on the old value. `waitForIdle` applies the
 * write without moving the clock, so it has to come first.
 */
private fun ComposeContentTestRule.applyAndAdvanceBy(millis: Long) {
    waitForIdle()
    mainClock.advanceTimeBy(millis)
}

private fun ComposeContentTestRule.applyAndAdvanceFrame() {
    waitForIdle()
    mainClock.advanceTimeByFrame()
}

/**
 * Robolectric cannot draw, so the clip itself is not asserted here - `CircularRevealTest` covers the
 * geometry. What this pins is the overlay's lifetime, which is what keeps the manager's back handler
 * and the pane focus suppression alive after the flag that opens it has already gone.
 */
class ManagerRevealOverlayTest : ComposeTest() {

    @Test
    fun `content composes on the frame the manager opens`() {
        var visible by mutableStateOf(false)
        var layerPresent: Boolean? = null

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            PreviewWrapper {
                RevealHarness(visible = visible, onState = { layerPresent = it.layerPresent })
            }
        }

        composeTestRule.applyAndAdvanceBy(1_000)
        composeTestRule.onNodeWithTag(CONTENT_TAG).assertDoesNotExist()
        layerPresent shouldBe false

        composeTestRule.runOnIdle { visible = true }
        composeTestRule.applyAndAdvanceFrame()

        composeTestRule.onNodeWithTag(CONTENT_TAG).assertExists()
        layerPresent shouldBe true
    }

    @Test
    fun `content stays composed through the exit`() {
        var visible by mutableStateOf(true)
        var layerPresent: Boolean? = null

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            PreviewWrapper {
                RevealHarness(visible = visible, onState = { layerPresent = it.layerPresent })
            }
        }

        composeTestRule.applyAndAdvanceBy(1_000)
        composeTestRule.onNodeWithTag(CONTENT_TAG).assertExists()

        composeTestRule.runOnIdle { visible = false }
        composeTestRule.applyAndAdvanceBy(100)

        composeTestRule.onNodeWithTag(CONTENT_TAG).assertExists()
        layerPresent shouldBe true

        composeTestRule.applyAndAdvanceBy(1_000)

        composeTestRule.onNodeWithTag(CONTENT_TAG).assertDoesNotExist()
        layerPresent shouldBe false
    }

    @Test
    fun `reopening mid-enter reverses instead of restarting`() {
        var visible by mutableStateOf(false)
        var layerPresent: Boolean? = null
        var state: ManagerRevealState? = null

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            PreviewWrapper {
                RevealHarness(
                    visible = visible,
                    onState = {
                        state = it
                        layerPresent = it.layerPresent
                    },
                )
            }
        }

        composeTestRule.applyAndAdvanceBy(1_000)
        composeTestRule.runOnIdle { visible = true }
        composeTestRule.applyAndAdvanceBy(100)
        composeTestRule.onNodeWithTag(CONTENT_TAG).assertExists()

        composeTestRule.runOnIdle { visible = false }
        composeTestRule.applyAndAdvanceBy(50)
        composeTestRule.onNodeWithTag(CONTENT_TAG).assertExists()
        layerPresent shouldBe true

        val midExit = composeTestRule.runOnIdle { state!!.progress.value }
        withClue("The exit has to be part-way through for the reversal to have somewhere to come back from") {
            midExit shouldBeGreaterThan 0.1f
        }

        composeTestRule.runOnIdle { visible = true }
        // No clock movement here on purpose: a restart snaps the reveal to 0 on the frame it begins,
        // a reversal keeps whatever the exit had left.
        composeTestRule.waitForIdle()
        withClue("Reopening restarted the reveal from zero instead of reversing the exit") {
            composeTestRule.runOnIdle { state!!.progress.value } shouldBe (midExit plusOrMinus 0.01f)
        }

        // Sampled per frame, not just at the end: a restart also arrives at 1f eventually, it just
        // travels up from zero to get there. The reopen still costs the one exit frame already in
        // flight, so the low-water mark sits a little below where the exit was interrupted.
        var lowest = midExit
        repeat(40) {
            composeTestRule.applyAndAdvanceFrame()
            lowest = minOf(lowest, composeTestRule.runOnIdle { state!!.progress.value })
        }
        composeTestRule.onNodeWithTag(CONTENT_TAG).assertExists()
        withClue("The reveal fell back towards zero instead of growing on from where the exit stopped") {
            lowest shouldBeGreaterThan midExit / 2f
        }

        composeTestRule.applyAndAdvanceBy(1_000)
        composeTestRule.onNodeWithTag(CONTENT_TAG).assertExists()
        layerPresent shouldBe true
        composeTestRule.runOnIdle { state!!.progress.value } shouldBe (1f plusOrMinus 0.001f)
    }

    @Test
    fun `revealSettled only reports once the enter has finished`() {
        var visible by mutableStateOf(false)
        var settled: Boolean? = null

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            PreviewWrapper {
                RevealHarness(visible = visible, onState = { settled = it.revealSettled })
            }
        }

        composeTestRule.applyAndAdvanceBy(1_000)
        composeTestRule.runOnIdle { visible = true }
        composeTestRule.applyAndAdvanceBy(100)

        composeTestRule.runOnIdle { settled shouldBe false }

        composeTestRule.applyAndAdvanceBy(1_000)

        composeTestRule.runOnIdle { settled shouldBe true }
    }

    @Test
    fun `input is blocked until the reveal settles`() {
        var visible by mutableStateOf(false)
        var clicks = 0

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            PreviewWrapper {
                RevealHarness(visible = visible, onContentClick = { clicks++ })
            }
        }

        composeTestRule.applyAndAdvanceBy(1_000)
        composeTestRule.runOnIdle { visible = true }
        composeTestRule.applyAndAdvanceBy(100)

        composeTestRule.onNodeWithTag(CONTENT_TAG).performClick()
        composeTestRule.runOnIdle { clicks shouldBe 0 }

        composeTestRule.applyAndAdvanceBy(1_000)

        composeTestRule.onNodeWithTag(CONTENT_TAG).performClick()
        composeTestRule.runOnIdle { clicks shouldBe 1 }
    }
}
