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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import eu.darken.butler.common.compose.PreviewWrapper
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

    @Composable
    private fun ActionRowCase(paneWidth: Dp, layoutDirection: LayoutDirection) {
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
            PreviewWrapper {
                Box(modifier = Modifier.size(width = paneWidth, height = 400.dp)) {
                    PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                        PaneBoundAlertDialog(
                            onDismissRequest = {},
                            confirmButton = { TextButton(onClick = {}) { Text("Confirm") } },
                            dismissButton = { TextButton(onClick = {}) { Text("Cancel") } },
                        )
                    }
                }
            }
        }
    }

    /**
     * Material's rule: dismiss sits before confirm while both fit on one line, and confirm moves
     * *above* dismiss once they don't. "Before" is the logical start side, so it mirrors in RTL.
     */
    @Test
    fun `the actions sit side by side while they fit`() {
        composeTestRule.setContent { ActionRowCase(WIDE_PANE, LayoutDirection.Ltr) }

        val confirm = composeTestRule.onNodeWithText("Confirm").getUnclippedBoundsInRoot()
        val dismiss = composeTestRule.onNodeWithText("Cancel").getUnclippedBoundsInRoot()

        confirm.top shouldBe dismiss.top
        (dismiss.left < confirm.left) shouldBe true
    }

    @Test
    fun `the actions mirror side by side in a right-to-left layout`() {
        composeTestRule.setContent { ActionRowCase(WIDE_PANE, LayoutDirection.Rtl) }

        val confirm = composeTestRule.onNodeWithText("Confirm").getUnclippedBoundsInRoot()
        val dismiss = composeTestRule.onNodeWithText("Cancel").getUnclippedBoundsInRoot()

        confirm.top shouldBe dismiss.top
        // Confirm still sits at the logical end, which is the physical left here
        (confirm.left < dismiss.left) shouldBe true
    }

    @Test
    fun `the confirm action wraps above the dismiss action`() {
        composeTestRule.setContent { ActionRowCase(NARROW_PANE, LayoutDirection.Ltr) }

        val confirm = composeTestRule.onNodeWithText("Confirm").getUnclippedBoundsInRoot()
        val dismiss = composeTestRule.onNodeWithText("Cancel").getUnclippedBoundsInRoot()

        (confirm.top < dismiss.top) shouldBe true
        // Both hug the logical end, which is the physical right here
        val surfaceBounds = composeTestRule.onNodeWithTag(surface).getUnclippedBoundsInRoot()
        (surfaceBounds.right - confirm.right < confirm.left - surfaceBounds.left) shouldBe true
        (surfaceBounds.right - dismiss.right < dismiss.left - surfaceBounds.left) shouldBe true
    }

    @Test
    fun `the wrapped actions mirror in a right-to-left layout`() {
        composeTestRule.setContent { ActionRowCase(NARROW_PANE, LayoutDirection.Rtl) }

        val confirm = composeTestRule.onNodeWithText("Confirm").getUnclippedBoundsInRoot()
        val dismiss = composeTestRule.onNodeWithText("Cancel").getUnclippedBoundsInRoot()

        // Confirm stays above dismiss...
        (confirm.top < dismiss.top) shouldBe true
        // ...and both hug the logical end, which is the physical left here — the exact mirror of
        // the assertion above, so neither test can pass without the row actually mirroring
        val surfaceBounds = composeTestRule.onNodeWithTag(surface).getUnclippedBoundsInRoot()
        (confirm.left - surfaceBounds.left < surfaceBounds.right - confirm.right) shouldBe true
        (dismiss.left - surfaceBounds.left < surfaceBounds.right - dismiss.right) shouldBe true
    }

    /**
     * A caller may pass an action slot that emits nothing — a selection dialog with only a cancel,
     * or one that dismisses on pick and has no actions at all. Measuring such a slot used to throw.
     */
    @Composable
    private fun EmptySlotCase(withConfirm: Boolean, withDismiss: Boolean) {
        PreviewWrapper {
            Box(modifier = Modifier.size(width = 400.dp, height = 400.dp)) {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneBoundAlertDialog(
                        onDismissRequest = {},
                        title = { Text("Title") },
                        confirmButton = { if (withConfirm) TextButton(onClick = {}) { Text("Confirm") } },
                        dismissButton = { if (withDismiss) TextButton(onClick = {}) { Text("Cancel") } },
                    )
                }
            }
        }
    }

    @Test
    fun `an empty confirm slot leaves the dismiss action end-aligned`() {
        composeTestRule.setContent { EmptySlotCase(withConfirm = false, withDismiss = true) }

        val surfaceBounds = composeTestRule.onNodeWithTag(surface).getUnclippedBoundsInRoot()
        val dismiss = composeTestRule.onNodeWithText("Cancel").getUnclippedBoundsInRoot()

        (surfaceBounds.right - dismiss.right < dismiss.left - surfaceBounds.left) shouldBe true
    }

    @Test
    fun `an empty dismiss slot leaves the confirm action end-aligned`() {
        composeTestRule.setContent { EmptySlotCase(withConfirm = true, withDismiss = false) }

        val surfaceBounds = composeTestRule.onNodeWithTag(surface).getUnclippedBoundsInRoot()
        val confirm = composeTestRule.onNodeWithText("Confirm").getUnclippedBoundsInRoot()

        (surfaceBounds.right - confirm.right < confirm.left - surfaceBounds.left) shouldBe true
    }

    @Test
    fun `a dialog with no actions at all still renders`() {
        composeTestRule.setContent { EmptySlotCase(withConfirm = false, withDismiss = false) }

        composeTestRule.onNodeWithTag(surface).assertExists()
        composeTestRule.onNodeWithText("Title").assertExists()
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

    companion object {
        private const val BEHIND_TAG = "behind"
        private val WIDE_PANE = 500.dp
        private val NARROW_PANE = 200.dp
        private const val HOST_TAG = "pane.host"
    }
}
