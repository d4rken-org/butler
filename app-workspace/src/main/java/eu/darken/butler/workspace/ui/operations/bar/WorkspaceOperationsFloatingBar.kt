package eu.darken.butler.workspace.ui.operations.bar

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.floatingbar.BarAnimation
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.BarScrollBehavior
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarScope
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarStackState
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import kotlin.time.Clock

/**
 * Packages [OperationsBar] as a floating bar: it owns the visibility rule, the active-vs-terminal
 * classification that picks the scroll behaviour, the animation, and the routing of a click on a
 * waiting operation to [OperationsBarAction.ShowConflict] instead of [OperationsBarAction.ShowDetails].
 *
 * [key] has no default because it is a per-workspace persistence contract: sharing one literal
 * would tie two workspaces' stored collapse fractions to each other.
 */
@Composable
fun FloatingBarScope.WorkspaceOperationsFloatingBar(
    key: String,
    operations: List<OperationDisplay>,
    onAction: (OperationsBarAction) -> Unit,
    initialExpanded: Boolean = false,
) {
    // Exhaustive rather than an is-chain: a future subtype would silently classify as terminal and
    // make an in-progress bar vanish on scroll with no compiler error.
    val hasActive = operations.any { op ->
        when (op.state) {
            is OperationDisplay.State.Queued,
            is OperationDisplay.State.Running,
            is OperationDisplay.State.Waiting -> true

            is OperationDisplay.State.Completed,
            is OperationDisplay.State.Failed,
            is OperationDisplay.State.Cancelled -> false
        }
    }

    FloatingBar(
        key = key,
        visible = operations.isNotEmpty(),
        scrollBehavior = if (hasActive) BarScrollBehavior.Static else BarScrollBehavior.VanishOnScroll,
        animation = BarAnimation.Slide(),
    ) {
        OperationsBar(
            operations = operations,
            onRequestCancelOperation = { onAction(OperationsBarAction.RequestCancel(it)) },
            onDismissOperation = { onAction(OperationsBarAction.Dismiss(it)) },
            onOperationClick = { operation ->
                when (operation.state) {
                    is OperationDisplay.State.Waiting -> onAction(OperationsBarAction.ShowConflict(operation.id))
                    else -> onAction(OperationsBarAction.ShowDetails(operation.id))
                }
            },
            onClearCompleted = { onAction(OperationsBarAction.ClearCompleted) },
            initialExpanded = initialExpanded,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceOperationsFloatingBarPreview() {
    PreviewWrapper {
        val stackState = rememberFloatingBarStackState(position = BarPosition.BOTTOM)
        FloatingBarStack(
            state = stackState,
            position = BarPosition.BOTTOM,
            bars = {
                WorkspaceOperationsFloatingBar(
                    key = "operations",
                    operations = listOf(
                        OperationDisplay(
                            id = Operation.Id(),
                            title = "Copying files".toCaString(),
                            description = "2 files remaining".toCaString(),
                            icon = Icons.TwoTone.ContentCopy,
                            state = OperationDisplay.State.Running(
                                primaryProgress = Progress.Data(
                                    primary = "Copying files".toCaString(),
                                    secondary = "Processing...".toCaString(),
                                    count = Progress.Count.Percent(8, 10),
                                ),
                            ),
                            canCancel = true,
                            startedAt = Clock.System.now(),
                        ),
                    ),
                    onAction = {},
                )
            },
        )
    }
}
