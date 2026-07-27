package eu.darken.butler.workspace.ui.dialogs

import android.content.pm.ApplicationInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.insets.LocalPaneEdges
import eu.darken.butler.workspace.ui.insets.paneHorizontalInsetPadding
import eu.darken.butler.workspace.ui.modal.LocalLayerActive
import eu.darken.butler.workspace.ui.modal.PaneLayer
import eu.darken.butler.workspace.ui.modal.WorkspaceBackHandler
import eu.darken.butler.workspace.ui.modal.requestPaneFocusOnPress

object PaneBoundAlertDialogDefaults {
    const val SCRIM_TEST_TAG = "workspace.dialog.panebound.scrim"
    const val SURFACE_TEST_TAG = "workspace.dialog.panebound.surface"
}

/**
 * Alert dialog bound to its parent pane instead of the window.
 *
 * Unlike Material's `AlertDialog`, which opens a new window over the whole screen, this stays
 * inside its pane: other panes remain visible and interactive, while within this pane it is a real
 * modal — it blocks touches, takes back, contains keyboard focus and hides the content behind it
 * from screen readers.
 *
 * Callers compose it conditionally. There is an enter animation but no exit animation, so removing
 * it from composition removes it immediately.
 *
 * ### [DialogProperties]
 * Three of the six fields describe a platform window and are meaningless for a pane-bound dialog.
 * - `dismissOnBackPress` — honored.
 * - `dismissOnClickOutside` — honored; the scrim always swallows the touch either way.
 * - `securePolicy` — NOT honored. There is no window to set `FLAG_SECURE` on, so a caller that
 *   needs screenshot blocking must stay a window dialog. Passing anything but
 *   [SecureFlagPolicy.Inherit] fails loudly in debug builds rather than silently pretending.
 * - `usePlatformDefaultWidth` — NOT honored; width comes from [widthIn] below.
 * - `decorFitsSystemWindows` — NOT honored; insets are handled by [includeImePadding].
 * - `windowTitle` — NOT honored; the accessibility pane title is set from app resources.
 *
 * @param includeImePadding pad the dialog above the soft keyboard. Enable for dialogs containing an
 *        editable text field; when `false` the dialog dismisses the keyboard as it appears.
 * @param neutralButton action placed at the *start* of the action row, away from confirm/dismiss.
 *        Material's `AlertDialog` has no equivalent slot — this is a deliberate divergence, not an
 *        oversight, and the reason a caller that needs a third action has to be pane-bound.
 */
@Composable
fun PaneBoundAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    neutralButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
    includeImePadding: Boolean = false,
) {
    val context = LocalContext.current
    val isDebuggable = remember(context) {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
    if (isDebuggable) {
        check(properties.securePolicy == SecureFlagPolicy.Inherit) {
            "PaneBoundAlertDialog cannot honor securePolicy=${properties.securePolicy}; " +
                "a dialog that must block screenshots has to stay a window dialog."
        }
    }

    // pointerInput(Unit) captures its lambda once, so a recomposition with a new callback or new
    // properties would otherwise keep dismissing through the stale ones.
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)
    val currentProperties by rememberUpdatedState(properties)

    // Only the Surface animates. Scaling the root would scale the scrim and the pointer barrier
    // too, uncovering the pane's edges and shrinking the blocked area during the entry animation.
    val surfaceVisibility = remember { MutableTransitionState(false) }
    surfaceVisibility.targetState = true

    val dialogPaneTitle = stringResource(eu.darken.butler.common.R.string.general_dialog_a11y_label)

    // A dialog without a text field deliberately dismisses focus and the keyboard as it appears,
    // so it must not have focus pushed into it by its own layer.
    PaneLayer(modifier = Modifier.fillMaxSize(), takeFocus = includeImePadding) {
        val layerActive = LocalLayerActive.current

        // Always registered, so back can never fall through to workspace navigation while the
        // dialog is up — it is swallowed even when dismissOnBackPress is false.
        WorkspaceBackHandler(enabled = true) {
            if (currentProperties.dismissOnBackPress) currentOnDismissRequest()
        }

        if (!includeImePadding) {
            val focusManager = LocalFocusManager.current
            val keyboardController = LocalSoftwareKeyboardController.current
            LaunchedEffect(layerActive) {
                if (!layerActive) return@LaunchedEffect
                focusManager.clearFocus()
                keyboardController?.hide()
            }
        }

        // Scrim + pointer barrier: full size from the first frame, never scaled.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(PaneBoundAlertDialogDefaults.SCRIM_TEST_TAG)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
                .requestPaneFocusOnPress()
                .pointerInput(Unit) {
                    detectTapGestures {
                        if (currentProperties.dismissOnClickOutside) currentOnDismissRequest()
                    }
                }
                .semantics {
                    paneTitle = dialogPaneTitle
                    isTraversalGroup = true
                },
        )

        // Only the centering container is inset — the scrim above stays full-pane, so it keeps
        // covering and blocking the strip next to a side navigation bar or a cutout.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .paneHorizontalInsetPadding(LocalPaneEdges.current)
                .then(if (includeImePadding) Modifier.imePadding() else Modifier)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            val maxSurfaceHeight = maxHeight

            AnimatedVisibility(
                visibleState = surfaceVisibility,
                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                exit = ExitTransition.None,
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(min = 280.dp, max = 560.dp)
                        .heightIn(max = maxSurfaceHeight)
                        .testTag(PaneBoundAlertDialogDefaults.SURFACE_TEST_TAG)
                        .requestPaneFocusOnPress()
                        // Taps on the dialog body must not reach the scrim and self-dismiss
                        .pointerInput(Unit) { detectTapGestures { } }
                        .then(modifier),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        // Title and text scroll; the action row must stay reachable no matter how
                        // long a translation or how large the font scale is.
                        Column(
                            modifier = Modifier
                                .weight(weight = 1f, fill = false)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            icon?.let {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.CenterHorizontally)
                                        .padding(bottom = 16.dp),
                                ) {
                                    it()
                                }
                            }

                            title?.let {
                                ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                                    Box(modifier = Modifier.padding(bottom = 16.dp)) { it() }
                                }
                            }

                            text?.let {
                                ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                                    Box(modifier = Modifier.padding(bottom = 8.dp)) { it() }
                                }
                            }
                        }

                        DialogActionRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            dismissButton = dismissButton,
                            confirmButton = confirmButton,
                            neutralButton = neutralButton,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Action row matching Material's `AlertDialogFlowRow`: dismiss before confirm while both fit on
 * one line, and confirm *above* dismiss once they don't. Positions are logical, so the row mirrors
 * in a right-to-left layout direction.
 *
 * `FlowRow` cannot express that — it fills rows in declaration order, so the wrapped order is
 * always the reverse of what Material does. With this few actions the layout is trivial enough to
 * do directly.
 *
 * An optional neutral action hugs the *start* edge while confirm and dismiss stay at the end. That
 * shape has no Material counterpart, so the two-action case below is kept as its own branch: the
 * four existing callers keep their own measure and placement arithmetic instead of relying on a
 * more general algorithm happening to collapse back onto the same numbers.
 *
 * Any slot may emit nothing — a caller can pass an empty `confirmButton` lambda for a dialog whose
 * only action is dismissal, or a selection dialog that dismisses on pick and has no actions at all.
 * A slot that emits no measurable is treated exactly like a missing one.
 *
 * ### Placement order is focus order
 * One-dimensional focus traversal sorts siblings by `LayoutNode.placeOrder`, not by composition
 * order, so the `placeRelative` calls in every branch below are written in the order the row reads
 * on screen and must not be reshuffled for readability — that is what keyboard Tab follows.
 * Placement order is also draw order, which is harmless here: two actions share a row only when
 * they fit into it, so they never overlap.
 */
@Composable
private fun DialogActionRow(
    dismissButton: (@Composable () -> Unit)?,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    neutralButton: (@Composable () -> Unit)? = null,
    spacing: Dp = 8.dp,
) {
    Layout(
        modifier = modifier,
        contents = listOf(
            { if (dismissButton != null) dismissButton() },
            confirmButton,
            { if (neutralButton != null) neutralButton() },
        ),
    ) { (dismissMeasurables, confirmMeasurables, neutralMeasurables), constraints ->
        val spacingPx = spacing.roundToPx()
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val dismiss = dismissMeasurables.firstOrNull()?.measure(childConstraints)
        val confirm = confirmMeasurables.firstOrNull()?.measure(childConstraints)
        val neutral = neutralMeasurables.firstOrNull()?.measure(childConstraints)

        val width = constraints.maxWidth

        // placeRelative, never place: x is measured from the layout's *start* edge, so the actions
        // mirror to the left in a right-to-left locale instead of being pinned to the physical
        // right. Arabic ships as a supported locale, so this is a real configuration.
        if (confirm == null) {
            // Whatever is left keeps the alignment it would have had beside a confirm action, so a
            // dismiss-only dialog does not suddenly look centered or start-aligned.
            val singleRow = neutral == null || dismiss == null ||
                neutral.width + spacingPx + dismiss.width <= width
            val height = when {
                neutral == null && dismiss == null -> 0
                neutral == null -> dismiss!!.height
                dismiss == null -> neutral.height
                singleRow -> maxOf(neutral.height, dismiss.height)
                else -> dismiss.height + spacingPx + neutral.height
            }

            layout(width, height) {
                if (singleRow) {
                    if (neutral != null) {
                        neutral.placeRelative(x = 0, y = (height - neutral.height) / 2)
                    }
                    if (dismiss != null) {
                        dismiss.placeRelative(x = width - dismiss.width, y = (height - dismiss.height) / 2)
                    }
                } else {
                    dismiss!!.placeRelative(x = width - dismiss.width, y = 0)
                    neutral!!.placeRelative(x = 0, y = dismiss.height + spacingPx)
                }
            }
        } else if (neutral == null) {
            val sideBySide = dismiss == null || dismiss.width + spacingPx + confirm.width <= width
            val height = when {
                dismiss == null -> confirm.height
                sideBySide -> maxOf(dismiss.height, confirm.height)
                else -> confirm.height + spacingPx + dismiss.height
            }

            layout(width, height) {
                if (sideBySide) {
                    dismiss?.placeRelative(
                        x = width - confirm.width - spacingPx - dismiss.width,
                        y = (height - dismiss.height) / 2,
                    )
                    confirm.placeRelative(x = width - confirm.width, y = (height - confirm.height) / 2)
                } else {
                    confirm.placeRelative(x = width - confirm.width, y = 0)
                    dismiss!!.placeRelative(x = width - dismiss.width, y = confirm.height + spacingPx)
                }
            }
        } else if (dismiss == null) {
            val singleRow = neutral.width + spacingPx + confirm.width <= width
            val height = when {
                singleRow -> maxOf(neutral.height, confirm.height)
                else -> confirm.height + spacingPx + neutral.height
            }

            layout(width, height) {
                if (singleRow) {
                    neutral.placeRelative(x = 0, y = (height - neutral.height) / 2)
                    confirm.placeRelative(x = width - confirm.width, y = (height - confirm.height) / 2)
                } else {
                    confirm.placeRelative(x = width - confirm.width, y = 0)
                    neutral.placeRelative(x = 0, y = confirm.height + spacingPx)
                }
            }
        } else {
            val endRowWidth = dismiss.width + spacingPx + confirm.width
            val singleRow = neutral.width + spacingPx + endRowWidth <= width
            val endRowFits = endRowWidth <= width
            val height = when {
                singleRow -> maxOf(neutral.height, maxOf(dismiss.height, confirm.height))
                // Dismiss and confirm still share a row, the neutral action drops below them
                endRowFits -> maxOf(dismiss.height, confirm.height) + spacingPx + neutral.height
                else -> confirm.height + spacingPx + dismiss.height + spacingPx + neutral.height
            }

            layout(width, height) {
                when {
                    singleRow -> {
                        neutral.placeRelative(x = 0, y = (height - neutral.height) / 2)
                        dismiss.placeRelative(
                            x = width - confirm.width - spacingPx - dismiss.width,
                            y = (height - dismiss.height) / 2,
                        )
                        confirm.placeRelative(x = width - confirm.width, y = (height - confirm.height) / 2)
                    }

                    endRowFits -> {
                        val endRowHeight = maxOf(dismiss.height, confirm.height)
                        dismiss.placeRelative(
                            x = width - confirm.width - spacingPx - dismiss.width,
                            y = (endRowHeight - dismiss.height) / 2,
                        )
                        confirm.placeRelative(
                            x = width - confirm.width,
                            y = (endRowHeight - confirm.height) / 2,
                        )
                        neutral.placeRelative(x = 0, y = endRowHeight + spacingPx)
                    }

                    else -> {
                        confirm.placeRelative(x = width - confirm.width, y = 0)
                        dismiss.placeRelative(x = width - dismiss.width, y = confirm.height + spacingPx)
                        neutral.placeRelative(
                            x = 0,
                            y = confirm.height + spacingPx + dismiss.height + spacingPx,
                        )
                    }
                }
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PaneBoundAlertDialogPreview() {
    PaneBoundDialogPreviewFrame {
        PaneBoundAlertDialog(
            onDismissRequest = {},
            title = { Text("Confirmation Dialog") },
            text = {
                Text("This dialog is bound to the pane and won't appear over other workspaces in multi-pane layouts.")
            },
            confirmButton = { TextButton(onClick = {}) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = {}) { Text("Cancel") } },
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PaneBoundAlertDialogWithIconPreview() {
    PaneBoundDialogPreviewFrame {
        PaneBoundAlertDialog(
            onDismissRequest = {},
            icon = { Text("!") },
            title = { Text("Delete 3 items?") },
            text = { Text("The selected items will be moved to the trash.") },
            confirmButton = { TextButton(onClick = {}) { Text("Delete") } },
            dismissButton = { TextButton(onClick = {}) { Text("Cancel") } },
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PaneBoundAlertDialogLongLabelsPreview() {
    PaneBoundDialogPreviewFrame {
        PaneBoundAlertDialog(
            onDismissRequest = {},
            title = { Text("Overwrite the existing file?") },
            text = { Text("A file with the same name already exists at the destination.") },
            confirmButton = { TextButton(onClick = {}) { Text("Overwrite everything") } },
            dismissButton = { TextButton(onClick = {}) { Text("Keep both copies instead") } },
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PaneBoundAlertDialogWithoutConfirmActionPreview() {
    PaneBoundDialogPreviewFrame {
        PaneBoundAlertDialog(
            onDismissRequest = {},
            title = { Text("Text encoding") },
            text = { Text("Pick an encoding to reopen this file with.") },
            confirmButton = {},
            dismissButton = { TextButton(onClick = {}) { Text("Cancel") } },
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PaneBoundAlertDialogWithImePaddingPreview() {
    PaneBoundDialogPreviewFrame {
        PaneBoundAlertDialog(
            onDismissRequest = {},
            includeImePadding = true,
            title = { Text("Rename") },
            text = { Text("Enter a new name for this file.") },
            confirmButton = { TextButton(onClick = {}) { Text("Rename") } },
            dismissButton = { TextButton(onClick = {}) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PaneBoundDialogPreviewFrame(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Text(
            text = "Workspace content underneath",
            modifier = Modifier.padding(16.dp),
        )
        content()
    }
}
