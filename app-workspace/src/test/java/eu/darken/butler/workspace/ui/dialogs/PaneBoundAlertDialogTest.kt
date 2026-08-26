package eu.darken.butler.workspace.ui.dialogs

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.LocalWorkspaceFocusRequest
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class PaneBoundAlertDialogTest : ComposeTest() {

    private val scrim = PaneBoundAlertDialogDefaults.SCRIM_TEST_TAG
    private val surface = PaneBoundAlertDialogDefaults.SURFACE_TEST_TAG

    @Test
    fun `back dismisses the dialog`() {
        var dismissed = false
        var dispatcher: OnBackPressedDispatcher? = null

        composeTestRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneBoundAlertDialog(
                        onDismissRequest = { dismissed = true },
                        confirmButton = { TextButton(onClick = {}) { Text("OK") } },
                    )
                }
            }
        }

        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }

        composeTestRule.runOnIdle { dismissed shouldBe true }
    }

    @Test
    fun `back is swallowed when dismissOnBackPress is off`() {
        var dismissed = false
        var outerBackFired = false
        var dispatcher: OnBackPressedDispatcher? = null

        composeTestRule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    androidx.activity.compose.BackHandler(enabled = true) { outerBackFired = true }
                    PaneBoundAlertDialog(
                        onDismissRequest = { dismissed = true },
                        properties = DialogProperties(dismissOnBackPress = false),
                        confirmButton = { TextButton(onClick = {}) { Text("OK") } },
                    )
                }
            }
        }

        composeTestRule.runOnIdle { dispatcher!!.onBackPressed() }

        composeTestRule.runOnIdle {
            dismissed shouldBe false
            outerBackFired shouldBe false
        }
    }

    @Test
    fun `tapping the scrim dismisses the dialog`() {
        var dismissed = false

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneBoundAlertDialog(
                        onDismissRequest = { dismissed = true },
                        confirmButton = { TextButton(onClick = {}) { Text("OK") } },
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag(scrim).performTouchInput { click(Offset(4f, 4f)) }

        composeTestRule.runOnIdle { dismissed shouldBe true }
    }

    @Test
    fun `the scrim swallows touches even when dismissOnClickOutside is off`() {
        var dismissed = false
        var behindClicked = false

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(BEHIND_TAG)
                            .clickable { behindClicked = true },
                    )
                    PaneBoundAlertDialog(
                        onDismissRequest = { dismissed = true },
                        properties = DialogProperties(dismissOnClickOutside = false),
                        confirmButton = { TextButton(onClick = {}) { Text("OK") } },
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag(scrim).performTouchInput { click(Offset(4f, 4f)) }

        composeTestRule.runOnIdle {
            dismissed shouldBe false
            behindClicked shouldBe false
        }
    }

    @Test
    fun `tapping the dialog body does not dismiss it`() {
        var dismissed = false

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneBoundAlertDialog(
                        onDismissRequest = { dismissed = true },
                        title = { Text("Title") },
                        confirmButton = { TextButton(onClick = {}) { Text("OK") } },
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag(surface).performTouchInput { click() }

        composeTestRule.runOnIdle { dismissed shouldBe false }
    }

    @Test
    fun `buttons inside the dialog still receive clicks`() {
        var confirmed = false
        var cancelled = false

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneBoundAlertDialog(
                        onDismissRequest = {},
                        confirmButton = { TextButton(onClick = { confirmed = true }) { Text("Confirm") } },
                        dismissButton = { TextButton(onClick = { cancelled = true }) { Text("Cancel") } },
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Confirm").performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()

        composeTestRule.runOnIdle {
            confirmed shouldBe true
            cancelled shouldBe true
        }
    }

    @Test
    fun `a text field inside the dialog still accepts input`() {
        var value by mutableStateOf("start")

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneBoundAlertDialog(
                        onDismissRequest = {},
                        includeImePadding = true,
                        text = {
                            OutlinedTextField(value = value, onValueChange = { value = it })
                        },
                        confirmButton = { TextButton(onClick = {}) { Text("OK") } },
                    )
                }
            }
        }

        composeTestRule.onNode(hasSetTextAction()).performTextClearance()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("renamed")

        composeTestRule.runOnIdle { value shouldBe "renamed" }
    }

    @Test
    fun `recomposition picks up a new callback and new properties`() {
        var firstDismissed = false
        var secondDismissed = false
        var swapped by mutableStateOf(false)

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneBoundAlertDialog(
                        onDismissRequest = { if (swapped) secondDismissed = true else firstDismissed = true },
                        properties = DialogProperties(dismissOnClickOutside = !swapped),
                        confirmButton = { TextButton(onClick = {}) { Text("OK") } },
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag(scrim).performTouchInput { click(Offset(4f, 4f)) }
        composeTestRule.runOnIdle { firstDismissed shouldBe true }

        composeTestRule.runOnIdle { swapped = true }
        composeTestRule.onNodeWithTag(scrim).performTouchInput { click(Offset(4f, 4f)) }

        // The swapped properties turn outside-dismissal off, so the new callback must not fire
        composeTestRule.runOnIdle { secondDismissed shouldBe false }
    }

    @Test
    fun `the surface fits a pane narrower than its minimum width`() {
        composeTestRule.setContent {
            PreviewWrapper {
                Box(modifier = Modifier.size(240.dp)) {
                    PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                        PaneBoundAlertDialog(
                            onDismissRequest = {},
                            title = { Text("Title") },
                            confirmButton = { TextButton(onClick = {}) { Text("OK") } },
                        )
                    }
                }
            }
        }

        val surfaceWidth = composeTestRule.onNodeWithTag(surface).getUnclippedBoundsInRoot().width
        (surfaceWidth <= 240.dp) shouldBe true
    }

    @Test
    fun `the pointer barrier covers the whole pane from the first frame`() {
        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            PreviewWrapper {
                Box(modifier = Modifier.size(width = 200.dp, height = 300.dp)) {
                    PaneLayerHost(
                        modifier = Modifier.fillMaxSize().testTag(HOST_TAG),
                        paneFocused = true,
                    ) {
                        PaneBoundAlertDialog(
                            onDismissRequest = {},
                            title = { Text("Title") },
                            confirmButton = { TextButton(onClick = {}) { Text("OK") } },
                        )
                    }
                }
            }
        }

        composeTestRule.mainClock.advanceTimeByFrame()

        val paneBounds = composeTestRule.onNodeWithTag(HOST_TAG).getUnclippedBoundsInRoot()
        val scrimBounds = composeTestRule.onNodeWithTag(scrim).getUnclippedBoundsInRoot()
        scrimBounds.width shouldBe paneBounds.width
        scrimBounds.height shouldBe paneBounds.height
    }

    /**
     * The dialog's own press observers have to inherit the pane's press gate, not just sit under a
     * boundary that happens to consume first.
     *
     * The pane-focus count is what shows the difference. Those observers read the down with
     * consumption ignored, so a scrim or button tap still asks for the pane after the boundary has
     * consumed it — the dismissal being withheld would be the boundary's doing, but a request
     * arriving would be the dialog's own.
     */
    @Test
    fun `a dialog withholds the presses its pane withholds`() {
        var dismissed = 0
        var confirmed = 0
        var paneFocusRequests = 0

        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalWorkspaceFocusRequest provides { paneFocusRequests++ },
                ) {
                    PaneLayerHost(
                        modifier = Modifier.fillMaxSize(),
                        paneFocused = true,
                        allowPresses = { false },
                    ) {
                        PaneBoundAlertDialog(
                            onDismissRequest = { dismissed++ },
                            title = { Text("Title") },
                            confirmButton = { TextButton(onClick = { confirmed++ }) { Text("OK") } },
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(scrim).performTouchInput { click(Offset(4f, 4f)) }
        composeTestRule.onNodeWithTag(surface).performClick()
        composeTestRule.onNodeWithText("OK").performClick()

        composeTestRule.runOnIdle {
            dismissed shouldBe 0
            confirmed shouldBe 0
            paneFocusRequests shouldBe 0
        }
    }

    companion object {
        private const val BEHIND_TAG = "behind"
        private const val HOST_TAG = "pane.host"
    }
}
