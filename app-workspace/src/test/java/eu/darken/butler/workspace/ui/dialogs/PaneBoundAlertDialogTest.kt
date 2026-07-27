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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
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

    @Composable
    private fun ActionStub(tag: String, size: DpSize) {
        Box(modifier = Modifier.size(size).testTag(tag))
    }

    /**
     * Bounds of a sized stub, guarded against clamping.
     *
     * `Modifier.size` is coerced into the incoming constraints, and the pane is itself capped by
     * the test screen, so a stub wider than the action row would come out exactly row-wide and sit
     * flush against both edges. The start/end assertions would then compare a distance with itself
     * and turn green or red for reasons that have nothing to do with the tier logic — this fails
     * loudly instead if the screen size or the dialog's padding ever changes.
     */
    private fun stubBounds(tag: String, requested: DpSize): DpRect {
        val bounds = composeTestRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        bounds.width shouldBe requested.width
        return bounds
    }

    /**
     * Fixed-size stubs instead of real buttons: Robolectric's text metrics are fake, so button
     * widths there say nothing about which wrapping tier a real device picks. Sized boxes make the
     * tier deterministic and let the heights differ.
     */
    @Composable
    private fun NeutralActionRowCase(
        paneWidth: Dp,
        layoutDirection: LayoutDirection,
        neutral: DpSize,
        confirm: DpSize,
        dismiss: DpSize?,
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
            PreviewWrapper {
                Box(modifier = Modifier.size(width = paneWidth, height = 400.dp)) {
                    PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                        PaneBoundAlertDialog(
                            onDismissRequest = {},
                            confirmButton = { ActionStub(CONFIRM_TAG, confirm) },
                            dismissButton = if (dismiss != null) {
                                { ActionStub(DISMISS_TAG, dismiss) }
                            } else {
                                null
                            },
                            neutralButton = { ActionStub(NEUTRAL_TAG, neutral) },
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `all three actions share a row while they fit`() {
        composeTestRule.setContent {
            NeutralActionRowCase(
                paneWidth = WIDE_PANE,
                layoutDirection = LayoutDirection.Ltr,
                neutral = SMALL_ACTION,
                confirm = SMALL_ACTION,
                dismiss = SMALL_ACTION,
            )
        }

        val neutral = stubBounds(NEUTRAL_TAG, SMALL_ACTION)
        val dismiss = stubBounds(DISMISS_TAG, SMALL_ACTION)
        val confirm = stubBounds(CONFIRM_TAG, SMALL_ACTION)

        neutral.top shouldBe dismiss.top
        dismiss.top shouldBe confirm.top
        // Neutral is start-most, then dismiss, then confirm at the end
        (neutral.left < dismiss.left) shouldBe true
        (dismiss.left < confirm.left) shouldBe true
    }

    @Test
    fun `all three actions mirror in a right-to-left layout`() {
        composeTestRule.setContent {
            NeutralActionRowCase(
                paneWidth = WIDE_PANE,
                layoutDirection = LayoutDirection.Rtl,
                neutral = SMALL_ACTION,
                confirm = SMALL_ACTION,
                dismiss = SMALL_ACTION,
            )
        }

        val neutral = stubBounds(NEUTRAL_TAG, SMALL_ACTION)
        val dismiss = stubBounds(DISMISS_TAG, SMALL_ACTION)
        val confirm = stubBounds(CONFIRM_TAG, SMALL_ACTION)

        neutral.top shouldBe dismiss.top
        dismiss.top shouldBe confirm.top
        // The exact mirror: the logical start is the physical right here
        (confirm.left < dismiss.left) shouldBe true
        (dismiss.left < neutral.left) shouldBe true
    }

    @Test
    fun `the neutral action drops below a row that still holds dismiss and confirm`() {
        composeTestRule.setContent {
            NeutralActionRowCase(
                paneWidth = WIDE_PANE,
                layoutDirection = LayoutDirection.Ltr,
                neutral = WIDE_ACTION,
                confirm = SMALL_ACTION,
                dismiss = SMALL_ACTION,
            )
        }

        val neutral = stubBounds(NEUTRAL_TAG, WIDE_ACTION)
        val dismiss = stubBounds(DISMISS_TAG, SMALL_ACTION)
        val confirm = stubBounds(CONFIRM_TAG, SMALL_ACTION)
        val surfaceBounds = composeTestRule.onNodeWithTag(surface).getUnclippedBoundsInRoot()

        dismiss.top shouldBe confirm.top
        (dismiss.left < confirm.left) shouldBe true
        (neutral.top >= dismiss.bottom) shouldBe true
        // Neutral hugs the logical start, which is the physical left here
        (neutral.left - surfaceBounds.left < surfaceBounds.right - neutral.right) shouldBe true
    }

    @Test
    fun `all three actions stack once dismiss and confirm no longer fit together`() {
        composeTestRule.setContent {
            NeutralActionRowCase(
                paneWidth = WIDE_PANE,
                layoutDirection = LayoutDirection.Ltr,
                neutral = SMALL_ACTION,
                confirm = TALL_ACTION,
                dismiss = LONG_ACTION,
            )
        }

        val neutral = stubBounds(NEUTRAL_TAG, SMALL_ACTION)
        val dismiss = stubBounds(DISMISS_TAG, LONG_ACTION)
        val confirm = stubBounds(CONFIRM_TAG, TALL_ACTION)
        val surfaceBounds = composeTestRule.onNodeWithTag(surface).getUnclippedBoundsInRoot()

        // Confirm above dismiss above neutral
        (confirm.top < dismiss.top) shouldBe true
        (dismiss.top < neutral.top) shouldBe true
        // Confirm and dismiss keep hugging the end, neutral the start
        (surfaceBounds.right - confirm.right < confirm.left - surfaceBounds.left) shouldBe true
        (surfaceBounds.right - dismiss.right < dismiss.left - surfaceBounds.left) shouldBe true
        (neutral.left - surfaceBounds.left < surfaceBounds.right - neutral.right) shouldBe true
    }

    @Test
    fun `a neutral action without a dismiss action shares the row while it fits`() {
        composeTestRule.setContent {
            NeutralActionRowCase(
                paneWidth = WIDE_PANE,
                layoutDirection = LayoutDirection.Ltr,
                neutral = SMALL_ACTION,
                confirm = SMALL_ACTION,
                dismiss = null,
            )
        }

        val neutral = stubBounds(NEUTRAL_TAG, SMALL_ACTION)
        val confirm = stubBounds(CONFIRM_TAG, SMALL_ACTION)
        val surfaceBounds = composeTestRule.onNodeWithTag(surface).getUnclippedBoundsInRoot()

        neutral.top shouldBe confirm.top
        (neutral.left - surfaceBounds.left < surfaceBounds.right - neutral.right) shouldBe true
        (surfaceBounds.right - confirm.right < confirm.left - surfaceBounds.left) shouldBe true
    }

    @Test
    fun `a neutral action without a dismiss action wraps below confirm`() {
        composeTestRule.setContent {
            NeutralActionRowCase(
                paneWidth = NARROW_PANE,
                layoutDirection = LayoutDirection.Ltr,
                neutral = MEDIUM_ACTION,
                confirm = MEDIUM_ACTION,
                dismiss = null,
            )
        }

        val neutral = stubBounds(NEUTRAL_TAG, MEDIUM_ACTION)
        val confirm = stubBounds(CONFIRM_TAG, MEDIUM_ACTION)
        val surfaceBounds = composeTestRule.onNodeWithTag(surface).getUnclippedBoundsInRoot()

        (confirm.top < neutral.top) shouldBe true
        (surfaceBounds.right - confirm.right < confirm.left - surfaceBounds.left) shouldBe true
        (neutral.left - surfaceBounds.left < surfaceBounds.right - neutral.right) shouldBe true
    }

    @Test
    fun `the neutral button still receives clicks`() {
        var neutralClicked = false

        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneBoundAlertDialog(
                        onDismissRequest = {},
                        confirmButton = { TextButton(onClick = {}) { Text("Confirm") } },
                        dismissButton = { TextButton(onClick = {}) { Text("Cancel") } },
                        neutralButton = { TextButton(onClick = { neutralClicked = true }) { Text("Clear") } },
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Clear").performClick()

        composeTestRule.runOnIdle { neutralClicked shouldBe true }
    }

    /**
     * Keyboard traversal sorts siblings by placement order, so the order of the `placeRelative`
     * calls in the action row is what Tab follows. Real buttons, not the sized stubs above: a plain
     * `Box` is not a focus target.
     */
    @Test
    fun `keyboard focus walks all three actions in reading order`() {
        var focusManager: FocusManager? = null

        composeTestRule.setContent {
            focusManager = LocalFocusManager.current
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneBoundAlertDialog(
                        onDismissRequest = {},
                        confirmButton = { TextButton(onClick = {}) { Text("Confirm") } },
                        dismissButton = { TextButton(onClick = {}) { Text("Cancel") } },
                        neutralButton = { TextButton(onClick = {}) { Text("Clear") } },
                    )
                }
            }
        }

        // The single-row tier is the one under test, so the actions must not have wrapped
        val neutral = composeTestRule.onNodeWithText("Clear").getUnclippedBoundsInRoot()
        val dismiss = composeTestRule.onNodeWithText("Cancel").getUnclippedBoundsInRoot()
        val confirm = composeTestRule.onNodeWithText("Confirm").getUnclippedBoundsInRoot()
        neutral.top shouldBe dismiss.top
        dismiss.top shouldBe confirm.top

        composeTestRule.onNodeWithText("Clear").requestFocus()
        composeTestRule.onNodeWithText("Clear").assertIsFocused()

        composeTestRule.runOnIdle { focusManager!!.moveFocus(FocusDirection.Next) }
        composeTestRule.onNodeWithText("Cancel").assertIsFocused()

        composeTestRule.runOnIdle { focusManager!!.moveFocus(FocusDirection.Next) }
        composeTestRule.onNodeWithText("Confirm").assertIsFocused()
    }

    /**
     * The wrapped tier is the one whose reading order differs from every other case: dismiss and
     * confirm keep the top row and the neutral action drops below them, so it is visited last.
     */
    @Test
    fun `keyboard focus reaches a wrapped neutral action last`() {
        var focusManager: FocusManager? = null

        composeTestRule.setContent {
            focusManager = LocalFocusManager.current
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneBoundAlertDialog(
                        onDismissRequest = {},
                        confirmButton = { TextButton(onClick = {}) { Text("Confirm") } },
                        dismissButton = { TextButton(onClick = {}) { Text("Cancel") } },
                        // A sized label rather than a long one: Robolectric's text metrics are fake,
                        // so only an explicit size reliably pushes the row into the wrapped tier —
                        // and the button around it stays a real focus target.
                        neutralButton = {
                            TextButton(onClick = {}) {
                                Text(text = "Clear", modifier = Modifier.size(WRAPPING_LABEL))
                            }
                        },
                    )
                }
            }
        }

        // The tier has to have held, or the traversal below would be the single-row one again
        val neutral = composeTestRule.onNodeWithText("Clear").getUnclippedBoundsInRoot()
        val dismiss = composeTestRule.onNodeWithText("Cancel").getUnclippedBoundsInRoot()
        val confirm = composeTestRule.onNodeWithText("Confirm").getUnclippedBoundsInRoot()
        dismiss.top shouldBe confirm.top
        (neutral.top >= dismiss.bottom) shouldBe true

        composeTestRule.onNodeWithText("Cancel").requestFocus()
        composeTestRule.onNodeWithText("Cancel").assertIsFocused()

        composeTestRule.runOnIdle { focusManager!!.moveFocus(FocusDirection.Next) }
        composeTestRule.onNodeWithText("Confirm").assertIsFocused()

        composeTestRule.runOnIdle { focusManager!!.moveFocus(FocusDirection.Next) }
        composeTestRule.onNodeWithText("Clear").assertIsFocused()
    }

    @Test
    fun `keyboard focus reaches dismiss before confirm`() {
        var focusManager: FocusManager? = null

        composeTestRule.setContent {
            focusManager = LocalFocusManager.current
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PaneBoundAlertDialog(
                        onDismissRequest = {},
                        confirmButton = { TextButton(onClick = {}) { Text("Confirm") } },
                        dismissButton = { TextButton(onClick = {}) { Text("Cancel") } },
                    )
                }
            }
        }

        val dismiss = composeTestRule.onNodeWithText("Cancel").getUnclippedBoundsInRoot()
        val confirm = composeTestRule.onNodeWithText("Confirm").getUnclippedBoundsInRoot()
        dismiss.top shouldBe confirm.top

        composeTestRule.onNodeWithText("Cancel").requestFocus()
        composeTestRule.onNodeWithText("Cancel").assertIsFocused()

        composeTestRule.runOnIdle { focusManager!!.moveFocus(FocusDirection.Next) }
        composeTestRule.onNodeWithText("Confirm").assertIsFocused()
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
        private const val NEUTRAL_TAG = "action.neutral"
        private const val DISMISS_TAG = "action.dismiss"
        private const val CONFIRM_TAG = "action.confirm"

        // The pane is capped by the test screen, so the action row inside the wide pane is only
        // about 224dp (screen − the dialog's two 24dp paddings on each side) and the narrow pane's
        // is about 104dp. Every stub stays well inside its row — a clamped stub would be flush with
        // both edges and say nothing about start/end affinity — while the combinations below still
        // clear or miss the row width by a wide margin. [stubBounds] fails if that ever stops
        // holding, so these numbers never have to be exact.
        // A neutral label wide enough that its button no longer fits beside the dismiss/confirm
        // pair — which is what puts the row into the wrapped tier — while still fitting the row.
        private val WRAPPING_LABEL = DpSize(width = 140.dp, height = 40.dp)

        private val SMALL_ACTION = DpSize(width = 60.dp, height = 48.dp)
        private val MEDIUM_ACTION = DpSize(width = 80.dp, height = 44.dp)
        private val WIDE_ACTION = DpSize(width = 140.dp, height = 40.dp)
        private val LONG_ACTION = DpSize(width = 120.dp, height = 48.dp)
        private val TALL_ACTION = DpSize(width = 130.dp, height = 56.dp)
    }
}
