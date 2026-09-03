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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.manager.WorkspaceRevealOrigin
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

        composeTestRule.mainClock.advanceTimeBy(1_000)
        composeTestRule.onNodeWithTag(CONTENT_TAG).assertDoesNotExist()
        layerPresent shouldBe false

        composeTestRule.runOnIdle { visible = true }
        composeTestRule.mainClock.advanceTimeByFrame()

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

        composeTestRule.mainClock.advanceTimeBy(1_000)
        composeTestRule.onNodeWithTag(CONTENT_TAG).assertExists()

        composeTestRule.runOnIdle { visible = false }
        composeTestRule.mainClock.advanceTimeBy(100)

        composeTestRule.onNodeWithTag(CONTENT_TAG).assertExists()
        layerPresent shouldBe true

        composeTestRule.mainClock.advanceTimeBy(1_000)

        composeTestRule.onNodeWithTag(CONTENT_TAG).assertDoesNotExist()
        layerPresent shouldBe false
    }

    @Test
    fun `reopening mid-enter reverses instead of restarting`() {
        var visible by mutableStateOf(false)
        var layerPresent: Boolean? = null

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            PreviewWrapper {
                RevealHarness(visible = visible, onState = { layerPresent = it.layerPresent })
            }
        }

        composeTestRule.mainClock.advanceTimeBy(1_000)
        composeTestRule.runOnIdle { visible = true }
        composeTestRule.mainClock.advanceTimeBy(100)
        composeTestRule.onNodeWithTag(CONTENT_TAG).assertExists()

        composeTestRule.runOnIdle { visible = false }
        composeTestRule.mainClock.advanceTimeBy(50)
        composeTestRule.onNodeWithTag(CONTENT_TAG).assertExists()
        layerPresent shouldBe true

        composeTestRule.runOnIdle { visible = true }
        composeTestRule.mainClock.advanceTimeBy(50)
        composeTestRule.onNodeWithTag(CONTENT_TAG).assertExists()

        composeTestRule.mainClock.advanceTimeBy(1_000)
        composeTestRule.onNodeWithTag(CONTENT_TAG).assertExists()
        layerPresent shouldBe true
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

        composeTestRule.mainClock.advanceTimeBy(1_000)
        composeTestRule.runOnIdle { visible = true }
        composeTestRule.mainClock.advanceTimeBy(100)

        composeTestRule.runOnIdle { settled shouldBe false }

        composeTestRule.mainClock.advanceTimeBy(1_000)

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

        composeTestRule.mainClock.advanceTimeBy(1_000)
        composeTestRule.runOnIdle { visible = true }
        composeTestRule.mainClock.advanceTimeBy(100)

        composeTestRule.onNodeWithTag(CONTENT_TAG).performClick()
        composeTestRule.runOnIdle { clicks shouldBe 0 }

        composeTestRule.mainClock.advanceTimeBy(1_000)

        composeTestRule.onNodeWithTag(CONTENT_TAG).performClick()
        composeTestRule.runOnIdle { clicks shouldBe 1 }
    }
}
