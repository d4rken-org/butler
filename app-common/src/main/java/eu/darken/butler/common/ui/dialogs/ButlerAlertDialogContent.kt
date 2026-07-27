package eu.darken.butler.common.ui.dialogs

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

/**
 * Content shell of an alert dialog: the surface, the scrollable title/text area and the action row.
 *
 * Host-agnostic on purpose — it knows nothing about windows, scrims, dismissal or
 * [androidx.compose.ui.window.DialogProperties]. A host wraps it and supplies those, so a dialog
 * looks and behaves the same whether it is presented in a platform window or bound to a pane.
 *
 * Title and text scroll; the action row stays outside the scroll region so it remains reachable no
 * matter how long a translation or how large the font scale is.
 *
 * Applies no test tag of its own — `Modifier.testTag` is a semantics property, so two of them on one
 * node means the later silently wins. Each host passes its own tag in via [modifier].
 *
 * @param neutralButton action placed at the *start* of the action row, away from confirm/dismiss.
 *        Material's `AlertDialog` has no equivalent slot.
 */
@Composable
fun ButlerAlertDialogContent(
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    neutralButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
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
                crossAxisSpacing = 8.dp,
            )
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
 * A slot that emits no measurable is treated exactly like a missing one. A slot that emits *more*
 * than one is a caller mistake: only the first would be laid out, so it fails loudly in debug
 * builds instead of dropping the rest.
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
    mainAxisSpacing: Dp = 8.dp,
    crossAxisSpacing: Dp = 12.dp,
) {
    val context = LocalContext.current
    val isDebuggable = remember(context) {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    Layout(
        modifier = modifier,
        contents = listOf(
            { if (dismissButton != null) dismissButton() },
            confirmButton,
            { if (neutralButton != null) neutralButton() },
        ),
    ) { (dismissMeasurables, confirmMeasurables, neutralMeasurables), constraints ->
        if (isDebuggable) {
            check(dismissMeasurables.size <= 1) {
                "dismissButton emitted ${dismissMeasurables.size} composables; " +
                    "an action slot may emit at most one, the rest would be dropped."
            }
            check(confirmMeasurables.size <= 1) {
                "confirmButton emitted ${confirmMeasurables.size} composables; " +
                    "an action slot may emit at most one, the rest would be dropped."
            }
            check(neutralMeasurables.size <= 1) {
                "neutralButton emitted ${neutralMeasurables.size} composables; " +
                    "an action slot may emit at most one, the rest would be dropped."
            }
        }

        val mainAxisSpacingPx = mainAxisSpacing.roundToPx()
        val crossAxisSpacingPx = crossAxisSpacing.roundToPx()
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
                neutral.width + mainAxisSpacingPx + dismiss.width <= width
            val height = when {
                neutral == null && dismiss == null -> 0
                neutral == null -> dismiss!!.height
                dismiss == null -> neutral.height
                singleRow -> maxOf(neutral.height, dismiss.height)
                else -> dismiss.height + crossAxisSpacingPx + neutral.height
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
                    neutral!!.placeRelative(x = 0, y = dismiss.height + crossAxisSpacingPx)
                }
            }
        } else if (neutral == null) {
            val sideBySide = dismiss == null || dismiss.width + mainAxisSpacingPx + confirm.width <= width
            val height = when {
                dismiss == null -> confirm.height
                sideBySide -> maxOf(dismiss.height, confirm.height)
                else -> confirm.height + crossAxisSpacingPx + dismiss.height
            }

            layout(width, height) {
                if (sideBySide) {
                    dismiss?.placeRelative(
                        x = width - confirm.width - mainAxisSpacingPx - dismiss.width,
                        y = (height - dismiss.height) / 2,
                    )
                    confirm.placeRelative(x = width - confirm.width, y = (height - confirm.height) / 2)
                } else {
                    confirm.placeRelative(x = width - confirm.width, y = 0)
                    dismiss!!.placeRelative(x = width - dismiss.width, y = confirm.height + crossAxisSpacingPx)
                }
            }
        } else if (dismiss == null) {
            val singleRow = neutral.width + mainAxisSpacingPx + confirm.width <= width
            val height = when {
                singleRow -> maxOf(neutral.height, confirm.height)
                else -> confirm.height + crossAxisSpacingPx + neutral.height
            }

            layout(width, height) {
                if (singleRow) {
                    neutral.placeRelative(x = 0, y = (height - neutral.height) / 2)
                    confirm.placeRelative(x = width - confirm.width, y = (height - confirm.height) / 2)
                } else {
                    confirm.placeRelative(x = width - confirm.width, y = 0)
                    neutral.placeRelative(x = 0, y = confirm.height + crossAxisSpacingPx)
                }
            }
        } else {
            val endRowWidth = dismiss.width + mainAxisSpacingPx + confirm.width
            val singleRow = neutral.width + mainAxisSpacingPx + endRowWidth <= width
            val endRowFits = endRowWidth <= width
            val height = when {
                singleRow -> maxOf(neutral.height, maxOf(dismiss.height, confirm.height))
                // Dismiss and confirm still share a row, the neutral action drops below them
                endRowFits -> maxOf(dismiss.height, confirm.height) + crossAxisSpacingPx + neutral.height
                else -> confirm.height + crossAxisSpacingPx + dismiss.height + crossAxisSpacingPx + neutral.height
            }

            layout(width, height) {
                when {
                    singleRow -> {
                        neutral.placeRelative(x = 0, y = (height - neutral.height) / 2)
                        dismiss.placeRelative(
                            x = width - confirm.width - mainAxisSpacingPx - dismiss.width,
                            y = (height - dismiss.height) / 2,
                        )
                        confirm.placeRelative(x = width - confirm.width, y = (height - confirm.height) / 2)
                    }

                    endRowFits -> {
                        val endRowHeight = maxOf(dismiss.height, confirm.height)
                        dismiss.placeRelative(
                            x = width - confirm.width - mainAxisSpacingPx - dismiss.width,
                            y = (endRowHeight - dismiss.height) / 2,
                        )
                        confirm.placeRelative(
                            x = width - confirm.width,
                            y = (endRowHeight - confirm.height) / 2,
                        )
                        neutral.placeRelative(x = 0, y = endRowHeight + crossAxisSpacingPx)
                    }

                    else -> {
                        confirm.placeRelative(x = width - confirm.width, y = 0)
                        dismiss.placeRelative(x = width - dismiss.width, y = confirm.height + crossAxisSpacingPx)
                        neutral.placeRelative(
                            x = 0,
                            y = confirm.height + crossAxisSpacingPx + dismiss.height + crossAxisSpacingPx,
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
private fun ButlerAlertDialogContentPreview() {
    PreviewWrapper {
        Box(modifier = Modifier.padding(24.dp)) {
            ButlerAlertDialogContent(
                modifier = Modifier.widthIn(min = 280.dp, max = 560.dp),
                title = { Text("Confirmation Dialog") },
                text = { Text("The content shell without any host around it.") },
                confirmButton = { TextButton(onClick = {}) { Text("Confirm") } },
                dismissButton = { TextButton(onClick = {}) { Text("Cancel") } },
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ButlerAlertDialogContentWithNeutralActionPreview() {
    PreviewWrapper {
        Box(modifier = Modifier.padding(24.dp)) {
            ButlerAlertDialogContent(
                modifier = Modifier.widthIn(min = 280.dp, max = 560.dp),
                icon = { Text("!") },
                title = { Text("Rename") },
                text = { Text("Enter a new name for this tab.") },
                confirmButton = { TextButton(onClick = {}) { Text("Rename") } },
                dismissButton = { TextButton(onClick = {}) { Text("Cancel") } },
                neutralButton = { TextButton(onClick = {}) { Text("Clear") } },
            )
        }
    }
}
