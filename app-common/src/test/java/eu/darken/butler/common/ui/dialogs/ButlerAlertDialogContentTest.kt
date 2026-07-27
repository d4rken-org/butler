package eu.darken.butler.common.ui.dialogs

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.Test
import testhelpers.ComposeTest

/**
 * Host-agnostic behaviour of the dialog shell: action row tiers, focus order and empty slots.
 *
 * No host around it — the shell is given a fixed width, so the action row is exactly the surface
 * width minus the shell's two 24dp paddings, derived instead of tuned against whatever width a
 * particular host happens to hand down.
 */
class ButlerAlertDialogContentTest : ComposeTest() {

    @Composable
    private fun ShellCase(
        surfaceWidth: Dp,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        surfaceMaxHeight: Dp = Dp.Unspecified,
        confirmButton: @Composable () -> Unit,
        dismissButton: (@Composable () -> Unit)? = null,
        neutralButton: (@Composable () -> Unit)? = null,
        icon: (@Composable () -> Unit)? = null,
        title: (@Composable () -> Unit)? = null,
        text: (@Composable () -> Unit)? = null,
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
            PreviewWrapper {
                // Scopes one-dimensional focus traversal to the dialog, the way a host's own focus
                // group does — without it `moveFocus` would walk the whole test root.
                Box(modifier = Modifier.focusGroup()) {
                    ButlerAlertDialogContent(
                        modifier = Modifier
                            .width(surfaceWidth)
                            .then(
                                if (surfaceMaxHeight == Dp.Unspecified) {
                                    Modifier
                                } else {
                                    // What both hosts do: bind the height to the viewport they got
                                    Modifier.heightIn(max = surfaceMaxHeight)
                                },
                            )
                            .testTag(SURFACE_TAG),
                        confirmButton = confirmButton,
                        dismissButton = dismissButton,
                        neutralButton = neutralButton,
                        icon = icon,
                        title = title,
                        text = text,
                    )
                }
            }
        }
    }

    @Composable
    private fun ActionRowCase(surfaceWidth: Dp, layoutDirection: LayoutDirection) {
        ShellCase(
            surfaceWidth = surfaceWidth,
            layoutDirection = layoutDirection,
            confirmButton = { TextButton(onClick = {}) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = {}) { Text("Cancel") } },
        )
    }

    /**
     * Material's rule: dismiss sits before confirm while both fit on one line, and confirm moves
     * *above* dismiss once they don't. "Before" is the logical start side, so it mirrors in RTL.
     */
    @Test
    fun `the actions sit side by side while they fit`() {
        composeTestRule.setContent { ActionRowCase(WIDE_SURFACE, LayoutDirection.Ltr) }

        val confirm = composeTestRule.onNodeWithText("Confirm").getUnclippedBoundsInRoot()
        val dismiss = composeTestRule.onNodeWithText("Cancel").getUnclippedBoundsInRoot()

        confirm.top shouldBe dismiss.top
        (dismiss.left < confirm.left) shouldBe true
    }

    @Test
    fun `the actions mirror side by side in a right-to-left layout`() {
        composeTestRule.setContent { ActionRowCase(WIDE_SURFACE, LayoutDirection.Rtl) }

        val confirm = composeTestRule.onNodeWithText("Confirm").getUnclippedBoundsInRoot()
        val dismiss = composeTestRule.onNodeWithText("Cancel").getUnclippedBoundsInRoot()

        confirm.top shouldBe dismiss.top
        // Confirm still sits at the logical end, which is the physical left here
        (confirm.left < dismiss.left) shouldBe true
    }

    @Test
    fun `the confirm action wraps above the dismiss action`() {
        composeTestRule.setContent { ActionRowCase(NARROW_SURFACE, LayoutDirection.Ltr) }

        val confirm = composeTestRule.onNodeWithText("Confirm").getUnclippedBoundsInRoot()
        val dismiss = composeTestRule.onNodeWithText("Cancel").getUnclippedBoundsInRoot()

        (confirm.top < dismiss.top) shouldBe true
        // Both hug the logical end, which is the physical right here
        val surfaceBounds = composeTestRule.onNodeWithTag(SURFACE_TAG).getUnclippedBoundsInRoot()
        (surfaceBounds.right - confirm.right < confirm.left - surfaceBounds.left) shouldBe true
        (surfaceBounds.right - dismiss.right < dismiss.left - surfaceBounds.left) shouldBe true
    }

    @Test
    fun `the wrapped actions mirror in a right-to-left layout`() {
        composeTestRule.setContent { ActionRowCase(NARROW_SURFACE, LayoutDirection.Rtl) }

        val confirm = composeTestRule.onNodeWithText("Confirm").getUnclippedBoundsInRoot()
        val dismiss = composeTestRule.onNodeWithText("Cancel").getUnclippedBoundsInRoot()

        // Confirm stays above dismiss...
        (confirm.top < dismiss.top) shouldBe true
        // ...and both hug the logical end, which is the physical left here — the exact mirror of
        // the assertion above, so neither test can pass without the row actually mirroring
        val surfaceBounds = composeTestRule.onNodeWithTag(SURFACE_TAG).getUnclippedBoundsInRoot()
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
     * `Modifier.size` is coerced into the incoming constraints, so a stub wider than the action row
     * would come out exactly row-wide and sit flush against both edges. The start/end assertions
     * would then compare a distance with itself and turn green or red for reasons that have nothing
     * to do with the tier logic — this fails loudly instead if the surface width or the shell's
     * padding ever changes.
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
        surfaceWidth: Dp,
        layoutDirection: LayoutDirection,
        neutral: DpSize,
        confirm: DpSize,
        dismiss: DpSize?,
    ) {
        ShellCase(
            surfaceWidth = surfaceWidth,
            layoutDirection = layoutDirection,
            confirmButton = { ActionStub(CONFIRM_TAG, confirm) },
            dismissButton = if (dismiss != null) {
                { ActionStub(DISMISS_TAG, dismiss) }
            } else {
                null
            },
            neutralButton = { ActionStub(NEUTRAL_TAG, neutral) },
        )
    }

    @Test
    fun `all three actions share a row while they fit`() {
        composeTestRule.setContent {
            NeutralActionRowCase(
                surfaceWidth = WIDE_SURFACE,
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
                surfaceWidth = WIDE_SURFACE,
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
                surfaceWidth = WIDE_SURFACE,
                layoutDirection = LayoutDirection.Ltr,
                neutral = WIDE_ACTION,
                confirm = SMALL_ACTION,
                dismiss = SMALL_ACTION,
            )
        }

        val neutral = stubBounds(NEUTRAL_TAG, WIDE_ACTION)
        val dismiss = stubBounds(DISMISS_TAG, SMALL_ACTION)
        val confirm = stubBounds(CONFIRM_TAG, SMALL_ACTION)
        val surfaceBounds = composeTestRule.onNodeWithTag(SURFACE_TAG).getUnclippedBoundsInRoot()

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
                surfaceWidth = WIDE_SURFACE,
                layoutDirection = LayoutDirection.Ltr,
                neutral = SMALL_ACTION,
                confirm = TALL_ACTION,
                dismiss = LONG_ACTION,
            )
        }

        val neutral = stubBounds(NEUTRAL_TAG, SMALL_ACTION)
        val dismiss = stubBounds(DISMISS_TAG, LONG_ACTION)
        val confirm = stubBounds(CONFIRM_TAG, TALL_ACTION)
        val surfaceBounds = composeTestRule.onNodeWithTag(SURFACE_TAG).getUnclippedBoundsInRoot()

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
                surfaceWidth = WIDE_SURFACE,
                layoutDirection = LayoutDirection.Ltr,
                neutral = SMALL_ACTION,
                confirm = SMALL_ACTION,
                dismiss = null,
            )
        }

        val neutral = stubBounds(NEUTRAL_TAG, SMALL_ACTION)
        val confirm = stubBounds(CONFIRM_TAG, SMALL_ACTION)
        val surfaceBounds = composeTestRule.onNodeWithTag(SURFACE_TAG).getUnclippedBoundsInRoot()

        neutral.top shouldBe confirm.top
        (neutral.left - surfaceBounds.left < surfaceBounds.right - neutral.right) shouldBe true
        (surfaceBounds.right - confirm.right < confirm.left - surfaceBounds.left) shouldBe true
    }

    @Test
    fun `a neutral action without a dismiss action wraps below confirm`() {
        composeTestRule.setContent {
            NeutralActionRowCase(
                surfaceWidth = NARROW_SURFACE,
                layoutDirection = LayoutDirection.Ltr,
                neutral = MEDIUM_ACTION,
                confirm = MEDIUM_ACTION,
                dismiss = null,
            )
        }

        val neutral = stubBounds(NEUTRAL_TAG, MEDIUM_ACTION)
        val confirm = stubBounds(CONFIRM_TAG, MEDIUM_ACTION)
        val surfaceBounds = composeTestRule.onNodeWithTag(SURFACE_TAG).getUnclippedBoundsInRoot()

        (confirm.top < neutral.top) shouldBe true
        (surfaceBounds.right - confirm.right < confirm.left - surfaceBounds.left) shouldBe true
        (neutral.left - surfaceBounds.left < surfaceBounds.right - neutral.right) shouldBe true
    }

    @Test
    fun `the neutral button still receives clicks`() {
        var neutralClicked = false

        composeTestRule.setContent {
            ShellCase(
                surfaceWidth = WIDE_SURFACE,
                confirmButton = { TextButton(onClick = {}) { Text("Confirm") } },
                dismissButton = { TextButton(onClick = {}) { Text("Cancel") } },
                neutralButton = { TextButton(onClick = { neutralClicked = true }) { Text("Clear") } },
            )
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
            ShellCase(
                surfaceWidth = WIDE_SURFACE,
                confirmButton = { TextButton(onClick = {}) { Text("Confirm") } },
                dismissButton = { TextButton(onClick = {}) { Text("Cancel") } },
                neutralButton = { TextButton(onClick = {}) { Text("Clear") } },
            )
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
            ShellCase(
                surfaceWidth = WIDE_SURFACE,
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
            ShellCase(
                surfaceWidth = WIDE_SURFACE,
                confirmButton = { TextButton(onClick = {}) { Text("Confirm") } },
                dismissButton = { TextButton(onClick = {}) { Text("Cancel") } },
            )
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
        ShellCase(
            surfaceWidth = WIDE_SURFACE,
            title = { Text("Title") },
            confirmButton = { if (withConfirm) TextButton(onClick = {}) { Text("Confirm") } },
            dismissButton = { if (withDismiss) TextButton(onClick = {}) { Text("Cancel") } },
        )
    }

    @Test
    fun `an empty confirm slot leaves the dismiss action end-aligned`() {
        composeTestRule.setContent { EmptySlotCase(withConfirm = false, withDismiss = true) }

        val surfaceBounds = composeTestRule.onNodeWithTag(SURFACE_TAG).getUnclippedBoundsInRoot()
        val dismiss = composeTestRule.onNodeWithText("Cancel").getUnclippedBoundsInRoot()

        (surfaceBounds.right - dismiss.right < dismiss.left - surfaceBounds.left) shouldBe true
    }

    @Test
    fun `an empty dismiss slot leaves the confirm action end-aligned`() {
        composeTestRule.setContent { EmptySlotCase(withConfirm = true, withDismiss = false) }

        val surfaceBounds = composeTestRule.onNodeWithTag(SURFACE_TAG).getUnclippedBoundsInRoot()
        val confirm = composeTestRule.onNodeWithText("Confirm").getUnclippedBoundsInRoot()

        (surfaceBounds.right - confirm.right < confirm.left - surfaceBounds.left) shouldBe true
    }

    @Test
    fun `a dialog with no actions at all still renders`() {
        composeTestRule.setContent { EmptySlotCase(withConfirm = false, withDismiss = false) }

        composeTestRule.onNodeWithTag(SURFACE_TAG).assertExists()
        composeTestRule.onNodeWithText("Title").assertExists()
    }

    /**
     * The scroll column has to fill the surface. A `Column` hands a non-stretched child
     * `minWidth = 0` and `weight` only constrains the cross axis, so without `fillMaxWidth` that
     * column wraps to its widest child — and `align(CenterHorizontally)` would centre the icon over
     * the *text* instead of over the dialog. Three shipping dialogs pass an icon.
     */
    @Test
    fun `the icon is centred in the dialog rather than over the text`() {
        composeTestRule.setContent {
            ShellCase(
                surfaceWidth = WIDE_SURFACE,
                icon = { ActionStub(ICON_TAG, ICON) },
                text = { ActionStub(TEXT_TAG, NARROW_TEXT) },
                confirmButton = { ActionStub(CONFIRM_TAG, SMALL_ACTION) },
            )
        }

        val surface = composeTestRule.onNodeWithTag(SURFACE_TAG).getUnclippedBoundsInRoot()
        val icon = stubBounds(ICON_TAG, ICON)
        val textBounds = stubBounds(TEXT_TAG, NARROW_TEXT)

        // Centred in the surface...
        (icon.left - surface.left) shouldBeAbout (surface.right - icon.right)
        // ...which the narrow, start-aligned text is deliberately not
        (textBounds.left - surface.left < surface.right - textBounds.right) shouldBe true
    }

    @Test
    fun `the title is start-aligned without an icon`() {
        composeTestRule.setContent {
            ShellCase(
                surfaceWidth = WIDE_SURFACE,
                title = { ActionStub(TITLE_TAG, NARROW_TEXT) },
                confirmButton = { ActionStub(CONFIRM_TAG, SMALL_ACTION) },
            )
        }

        val surface = composeTestRule.onNodeWithTag(SURFACE_TAG).getUnclippedBoundsInRoot()
        val titleBounds = stubBounds(TITLE_TAG, NARROW_TEXT)

        (titleBounds.left - surface.left) shouldBeAbout SHELL_PADDING
    }

    @Test
    fun `the title is centred under an icon`() {
        composeTestRule.setContent {
            ShellCase(
                surfaceWidth = WIDE_SURFACE,
                icon = { ActionStub(ICON_TAG, ICON) },
                title = { ActionStub(TITLE_TAG, NARROW_TEXT) },
                confirmButton = { ActionStub(CONFIRM_TAG, SMALL_ACTION) },
            )
        }

        val surface = composeTestRule.onNodeWithTag(SURFACE_TAG).getUnclippedBoundsInRoot()
        val titleBounds = stubBounds(TITLE_TAG, NARROW_TEXT)

        (titleBounds.left - surface.left) shouldBeAbout (surface.right - titleBounds.right)
    }

    /**
     * Robolectric has no native bitmap and `captureToImage()` deadlocks, so colours and styles are
     * read out of the composition instead of off a pixel: a probe in each slot captures what the
     * shell provided to it.
     */
    @Test
    fun `every content slot receives its material colour and style`() {
        var expectedIconColor: Color? = null
        var expectedTitleColor: Color? = null
        var expectedTextColor: Color? = null
        var expectedActionColor: Color? = null
        var expectedTitleStyle: TextStyle? = null
        var expectedTextStyle: TextStyle? = null
        var expectedActionStyle: TextStyle? = null

        var iconColor: Color? = null
        var titleColor: Color? = null
        var textColor: Color? = null
        var actionColor: Color? = null
        var titleStyle: TextStyle? = null
        var textStyle: TextStyle? = null
        var actionStyle: TextStyle? = null

        composeTestRule.setContent {
            PreviewWrapper {
                val ambient = LocalTextStyle.current
                expectedIconColor = AlertDialogDefaults.iconContentColor
                expectedTitleColor = AlertDialogDefaults.titleContentColor
                expectedTextColor = AlertDialogDefaults.textContentColor
                expectedActionColor = MaterialTheme.colorScheme.primary
                expectedTitleStyle = ambient.merge(MaterialTheme.typography.headlineSmall)
                expectedTextStyle = ambient.merge(MaterialTheme.typography.bodyMedium)
                expectedActionStyle = ambient.merge(MaterialTheme.typography.labelLarge)

                ButlerAlertDialogContent(
                    modifier = Modifier.width(WIDE_SURFACE),
                    icon = { iconColor = LocalContentColor.current },
                    title = {
                        titleColor = LocalContentColor.current
                        titleStyle = LocalTextStyle.current
                    },
                    text = {
                        textColor = LocalContentColor.current
                        textStyle = LocalTextStyle.current
                    },
                    confirmButton = {
                        actionColor = LocalContentColor.current
                        actionStyle = LocalTextStyle.current
                    },
                )
            }
        }

        composeTestRule.runOnIdle {
            iconColor shouldBe expectedIconColor
            titleColor shouldBe expectedTitleColor
            textColor shouldBe expectedTextColor
            actionColor shouldBe expectedActionColor
            titleStyle shouldBe expectedTitleStyle
            textStyle shouldBe expectedTextStyle
            actionStyle shouldBe expectedActionStyle
        }
    }

    /**
     * The row lays out only the first measurable per slot, so a slot with two siblings would
     * silently lose one. Harmless while the row was private; not once the shell is public.
     */
    @Test
    fun `an action slot emitting two composables fails loudly`() {
        val error = shouldThrowAny {
            composeTestRule.setContent {
                ShellCase(
                    surfaceWidth = WIDE_SURFACE,
                    confirmButton = {
                        ActionStub("first", SMALL_ACTION)
                        ActionStub("second", SMALL_ACTION)
                    },
                )
            }
            composeTestRule.waitForIdle()
        }

        error.messageChain() shouldContain "an action slot may emit at most one"
    }

    private fun Throwable.messageChain(): String = generateSequence(this) { it.cause }
        .mapNotNull { it.message }
        .joinToString(" | ")

    /**
     * The action row sits outside the scroll region, so three stacked actions plus the shell's two
     * 24dp paddings can be taller than the viewport the host bound the surface to. When that
     * happens the row keeps its full height and takes the space out of the shell's padding, rather
     * than being pushed past the surface edge — the actions are the only way out of the dialog.
     *
     * That padding is the whole budget: a row taller than the entire viewport would still escape.
     * Three stacked actions need 168dp here, so the viewport would have to be shorter than that
     * before it bit — which no host hands down in practice.
     */
    @Test
    fun `the action row stays inside the surface when it is taller than the viewport`() {
        composeTestRule.setContent {
            ShellCase(
                surfaceWidth = STACKING_SURFACE,
                surfaceMaxHeight = SHORT_VIEWPORT,
                text = { ActionStub(TEXT_TAG, TALL_TEXT) },
                confirmButton = { ActionStub(CONFIRM_TAG, LONG_ACTION) },
                dismissButton = { ActionStub(DISMISS_TAG, LONG_ACTION) },
                neutralButton = { ActionStub(NEUTRAL_TAG, LONG_ACTION) },
            )
        }

        val surface = composeTestRule.onNodeWithTag(SURFACE_TAG).getUnclippedBoundsInRoot()
        val actions = listOf(NEUTRAL_TAG, DISMISS_TAG, CONFIRM_TAG).map { stubBounds(it, LONG_ACTION) }

        // All three really did stack, or the case would not exercise the overflow at all
        actions.map { it.top }.distinct().size shouldBe 3
        // ...and the row plus the shell's padding really does not fit, or nothing is being tested
        val rowHeight = actions.maxOf { it.bottom } - actions.minOf { it.top }
        (rowHeight + SHELL_PADDING + SHELL_PADDING > surface.height) shouldBe true

        actions.forEach { action ->
            (action.top >= surface.top) shouldBe true
            (action.bottom <= surface.bottom) shouldBe true
        }
    }

    /**
     * Material's paddings, pinned. The title's 16dp is the only gap above the action row now — the
     * row's own 16dp top padding is gone, so this case is 16dp shorter than it used to be.
     */
    @Test
    fun `a dialog with a title and no text is exactly its parts tall`() {
        composeTestRule.setContent {
            ShellCase(
                surfaceWidth = WIDE_SURFACE,
                title = { ActionStub(TITLE_TAG, NARROW_TEXT) },
                confirmButton = { ActionStub(CONFIRM_TAG, SMALL_ACTION) },
            )
        }

        val surface = composeTestRule.onNodeWithTag(SURFACE_TAG).getUnclippedBoundsInRoot()

        surface.height shouldBeAbout
            (SHELL_PADDING + NARROW_TEXT.height + TITLE_PADDING + SMALL_ACTION.height + SHELL_PADDING)
    }

    /** Nothing above the row at all, so the shell is the row plus its two 24dp paddings. */
    @Test
    fun `a dialog with only actions is exactly its parts tall`() {
        composeTestRule.setContent {
            ShellCase(
                surfaceWidth = WIDE_SURFACE,
                confirmButton = { ActionStub(CONFIRM_TAG, SMALL_ACTION) },
            )
        }

        val surface = composeTestRule.onNodeWithTag(SURFACE_TAG).getUnclippedBoundsInRoot()

        surface.height shouldBeAbout (SHELL_PADDING + SMALL_ACTION.height + SHELL_PADDING)
    }

    /** Dp comparisons come off px-rounded bounds, so equality is asserted to within a pixel or so. */
    private infix fun Dp.shouldBeAbout(expected: Dp) {
        withClue("$this should be about $expected") {
            (kotlin.math.abs(this.value - expected.value) <= 1f) shouldBe true
        }
    }

    companion object {
        private const val SURFACE_TAG = "common.dialog.shell.surface"
        private const val NEUTRAL_TAG = "action.neutral"
        private const val DISMISS_TAG = "action.dismiss"
        private const val CONFIRM_TAG = "action.confirm"
        private const val ICON_TAG = "content.icon"
        private const val TITLE_TAG = "content.title"
        private const val TEXT_TAG = "content.text"

        /** The shell's own padding, on every edge, and the gap below the title. */
        private val SHELL_PADDING = 24.dp
        private val TITLE_PADDING = 16.dp

        // The shell has no host to size it here, so the surface width is given directly and the
        // action row is exactly that minus the shell's two 24dp paddings: 224dp wide, 104dp narrow.
        // Every stub stays well inside its row — a clamped stub would be flush with both edges and
        // say nothing about start/end affinity — while the combinations below still clear or miss
        // the row width by a wide margin. [stubBounds] fails if that ever stops holding, so these
        // numbers never have to be exact.
        private val WIDE_SURFACE = 272.dp
        private val NARROW_SURFACE = 152.dp

        // A neutral label wide enough that its button no longer fits beside the dismiss/confirm
        // pair — which is what puts the row into the wrapped tier — while still fitting the row.
        private val WRAPPING_LABEL = DpSize(width = 140.dp, height = 40.dp)

        private val SMALL_ACTION = DpSize(width = 60.dp, height = 48.dp)
        private val MEDIUM_ACTION = DpSize(width = 80.dp, height = 44.dp)
        private val WIDE_ACTION = DpSize(width = 140.dp, height = 40.dp)
        private val LONG_ACTION = DpSize(width = 120.dp, height = 48.dp)
        private val TALL_ACTION = DpSize(width = 130.dp, height = 56.dp)

        /** Well inside the 224dp content area, so a centred child is nowhere near a start-aligned one. */
        private val ICON = DpSize(width = 24.dp, height = 24.dp)
        private val NARROW_TEXT = DpSize(width = 80.dp, height = 32.dp)

        // 152dp content area against three 120dp actions: no tier fits, so they stack to
        // 48 + 12 + 48 + 12 + 48 = 168dp. With the shell's 48dp of padding that needs 216dp, more
        // than the viewport the surface is bound to — while the row on its own still fits inside it.
        private val STACKING_SURFACE = 200.dp
        private val SHORT_VIEWPORT = 192.dp
        private val TALL_TEXT = DpSize(width = 80.dp, height = 400.dp)
    }
}
