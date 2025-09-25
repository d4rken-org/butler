package eu.darken.butler.workspace.ui.operations

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.common.ui.SwipeToDismissItem
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.operations.Operation
import kotlin.time.Clock

@Composable
fun OperationsBar(
    operations: List<OperationDisplay>,
    onCancelOperation: (Operation.Id) -> Unit,
    onDismissOperation: (Operation.Id) -> Unit,
    onOperationClick: (OperationDisplay) -> Unit,
    onClearCompleted: () -> Unit,
    modifier: Modifier = Modifier,
    initialExpanded: Boolean = false,
) {
    // Preserve expansion state across operations changes
    var isExpanded by remember(operations.size > 1) {
        mutableStateOf(initialExpanded)
    }

    // State for cancel confirmation dialog
    var pendingCancelId by remember { mutableStateOf<Operation.Id?>(null) }

    // State for operation details sheet
    var selectedOperationId by remember { mutableStateOf<Operation.Id?>(null) }

    AnimatedVisibility(
        visible = operations.isNotEmpty(),
        modifier = modifier,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(16.dp)
                )
                .animateContentSize(),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        ) {
            Column {
                // Header for multiple operations
                if (operations.size > 1) {
                    OperationsBarHeader(
                        operationCount = operations.size,
                        completedCount = operations.count { it.state is OperationDisplay.State.Completed },
                        runningCount = operations.count { it.state is OperationDisplay.State.Running },
                        isExpanded = isExpanded,
                        onExpandClick = { isExpanded = !isExpanded },
                        onClearCompleted = onClearCompleted,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 32.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                }

                // Operations list
                val visibleOps = when {
                    !isExpanded -> operations.take(1)  // Show highest priority only
                    operations.size > 1 -> operations.reversed()  // Show all, reversed so highest priority is at bottom
                    else -> operations  // Single operation, no need to reverse
                }

                LazyColumn(
                    modifier = Modifier
                        .heightIn(max = if (visibleOps.size > 1) 240.dp else androidx.compose.ui.unit.Dp.Unspecified)
                        .fillMaxWidth(),
                    userScrollEnabled = visibleOps.size > 4  // Only enable scroll when needed
                ) {
                    itemsIndexed(
                        items = visibleOps,
                        key = { _, operation -> operation.id.longTag }
                    ) { index, operation ->
                        // Determine if operation is dismissible vs cancellable
                        val canDismiss = when (operation.state) {
                            is OperationDisplay.State.Completed,
                            is OperationDisplay.State.Failed,
                            is OperationDisplay.State.Cancelled -> true
                            else -> false
                        }

                        val canCancel = operation.canCancel && !canDismiss

                        SwipeToDismissItem(
                            enabled = canDismiss,
                            onDismiss = {
                                onDismissOperation(operation.id)
                            },
                            backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            dismissContent = {
                                Text(
                                    text = stringResource(eu.darken.butler.common.R.string.general_dismiss_action),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.TwoTone.Close,
                                    contentDescription = stringResource(eu.darken.butler.common.R.string.general_dismiss_action),
                                )
                            }
                        ) {
                            OperationEntryRow(
                                operation = operation,
                                onRowClick = { selectedOperationId = operation.id },
                                onActionClick = if (canCancel) {
                                    { pendingCancelId = operation.id }
                                } else null,
                            )
                        }

                        // Add divider after each entry except the last one
                        if (index < visibleOps.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 32.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            )
                        }
                    }
                }
            }
        }
    }

    // Cancel confirmation dialog
    pendingCancelId?.let { operationId ->
        AlertDialog(
            onDismissRequest = { pendingCancelId = null },
            title = { Text(stringResource(R.string.operations_cancel_dialog_title)) },
            text = { Text(stringResource(R.string.operations_cancel_dialog_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingCancelId = null
                        onCancelOperation(operationId)
                    }
                ) {
                    Text(stringResource(R.string.operations_cancel_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingCancelId = null }
                ) {
                    Text(stringResource(eu.darken.butler.common.R.string.general_cancel_action))
                }
            }
        )
    }

    // Operation details sheet
    selectedOperationId?.let { operationId ->
        val currentOperation = operations.find { it.id == operationId }
        currentOperation?.let { operation ->
            OperationDetailsSheet(
                operation = operation,
                onDismiss = { selectedOperationId = null },
                onCancel = if (operation.canCancel && operation.state is OperationDisplay.State.Running) {
                    {
                        selectedOperationId = null
                        pendingCancelId = operation.id
                    }
                } else null,
                onCopyError = if (operation.state is OperationDisplay.State.Failed) {
                    {
                        // TODO: Copy error details to clipboard
                        selectedOperationId = null
                    }
                } else null,
            )
        }
    }
}

@Preview2
@Composable
private fun OperationsBarPreview() {
    PreviewWrapper {
        val operations = listOf(
            OperationDisplay(
                id = Operation.Id(),
                title = "Deleting files".toCaString(),
                description = "3 files remaining".toCaString(),
                icon = Icons.TwoTone.Delete,
                state = OperationDisplay.State.Running(
                    primaryProgress = Progress.Data(
                        primary = "Deleting files".toCaString(),
                        secondary = "Processing files...".toCaString(),
                        count = Progress.Count.Percent(6, 10)
                    )
                ),
                canCancel = true,
                startedAt = Clock.System.now(),
            ),
            OperationDisplay(
                id = Operation.Id(),
                title = "Copy operation".toCaString(),
                description = null,
                icon = Icons.TwoTone.Delete,
                state = OperationDisplay.State.Completed(
                    summary = "Success".toCaString(),
                    completedAt = Clock.System.now(),
                    report = object : Operation.Report {
                        override val title = "Operation Complete".toCaString()
                        override val summary = "Success".toCaString()
                        override val affectedPaths = emptyList<Operation.Report.PathChange>()
                    }
                ),
                canCancel = false,
                startedAt = Clock.System.now(),
            ),
        )

        OperationsBar(
            operations = operations,
            onCancelOperation = {},
            onDismissOperation = {},
            onOperationClick = {},
            onClearCompleted = {},
        )
    }
}