package eu.darken.butler.workspace.ui.operations.bar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.common.ui.SwipeToDismissItem
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import kotlin.time.Clock

@Composable
fun OperationsBar(
    operations: List<OperationDisplay>,
    onRequestCancelOperation: (Operation.Id) -> Unit,
    onDismissOperation: (Operation.Id) -> Unit,
    onOperationClick: (OperationDisplay) -> Unit,
    onClearCompleted: () -> Unit,
    modifier: Modifier = Modifier,
    initialExpanded: Boolean = false,
) {
    // Preserve expansion state across operations changes
    var isExpanded by remember {
        mutableStateOf(initialExpanded)
    }

    // State for cascading clear completed animation
    var clearCompletedAnimationTrigger by remember { mutableLongStateOf(0L) }

    // Handle cascading clear completed animation
    LaunchedEffect(clearCompletedAnimationTrigger) {
        if (clearCompletedAnimationTrigger > 0L) {
            val completedOps = operations.filter { op ->
                when (op.state) {
                    is OperationDisplay.State.Completed,
                    is OperationDisplay.State.Failed,
                    is OperationDisplay.State.Cancelled -> true

                    else -> false
                }
            }
            // Wait for all swipe animations to complete before clearing
            val totalAnimationTime = (completedOps.size * 200L) + 500L
            kotlinx.coroutines.delay(totalAnimationTime)
            onClearCompleted()
            clearCompletedAnimationTrigger = 0L
        }
    }

    AnimatedVisibility(
        visible = operations.isNotEmpty(),
        modifier = modifier,
        enter = slideInVertically { it } + fadeIn(animationSpec = tween(300)),
        exit = slideOutVertically { it } + fadeOut(animationSpec = tween(300))
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(16.dp)
                ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        ) {
            Column(
                modifier = Modifier.animateContentSize(
                    animationSpec = tween(durationMillis = 300)
                )
            ) {
                OperationsBarHeader(
                    operationCount = operations.size,
                    completedCount = operations.count {
                        it.state is OperationDisplay.State.Completed ||
                            it.state is OperationDisplay.State.Failed ||
                            it.state is OperationDisplay.State.Cancelled
                    },
                    runningCount = operations.count { it.state is OperationDisplay.State.Running },
                    isExpanded = isExpanded,
                    onExpandClick = { isExpanded = !isExpanded },
                    onClearCompleted = {
                        if (isExpanded) {
                            clearCompletedAnimationTrigger = System.currentTimeMillis()
                        } else {
                            onClearCompleted()
                        }
                    },
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 32.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )

                // Operations list
                val visibleOps = when {
                    !isExpanded -> {
                        // When collapsed, prioritize operations needing attention
                        val waitingOps = operations.filter { it.state is OperationDisplay.State.Waiting }
                        val runningOps = operations.filter { it.state is OperationDisplay.State.Running }
                        when {
                            // Priority 1: Waiting operations (require user input, blocking)
                            waitingOps.isNotEmpty() -> {
                                if (waitingOps.size > 1) waitingOps.reversed() else waitingOps
                            }
                            // Priority 2: Running operations
                            runningOps.isNotEmpty() -> {
                                if (runningOps.size > 1) runningOps.reversed() else runningOps
                            }
                            // Fallback: Most recently started operation
                            else -> {
                                operations.maxByOrNull { it.startedAt }?.let { listOf(it) } ?: emptyList()
                            }
                        }
                    }
                    // When expanded, show all operations
                    operations.size > 1 -> operations.reversed()
                    else -> operations
                }

                LazyColumn(
                    modifier = Modifier
                        .heightIn(max = if (visibleOps.size > 1) 240.dp else Dp.Unspecified)
                        .fillMaxWidth(),
                    userScrollEnabled = visibleOps.size > 1  // Enable scroll when height is constrained
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

                        // Calculate dismiss delay for cascading animation
                        val completedOpsBeforeThis = operations.take(operations.indexOf(operation))
                            .count { op ->
                                when (op.state) {
                                    is OperationDisplay.State.Completed,
                                    is OperationDisplay.State.Failed,
                                    is OperationDisplay.State.Cancelled -> true

                                    else -> false
                                }
                            }
                        val dismissDelay = if (canDismiss) completedOpsBeforeThis * 200L else 0L

                        SwipeToDismissItem(
                            enabled = canDismiss,
                            onDismiss = {
                                onDismissOperation(operation.id)
                            },
                            backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            programmaticDismissTrigger = clearCompletedAnimationTrigger,
                            programmaticDismissDelay = dismissDelay,
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
                                onRowClick = { onOperationClick(operation) },
                                onActionClick = if (canCancel) {
                                    { onRequestCancelOperation(operation.id) }
                                } else null,
                                isBarExpanded = isExpanded,
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
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OperationsBarPreview() {
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
            description = "Copy operation description".toCaString(),
            icon = Icons.TwoTone.Delete,
            state = OperationDisplay.State.Completed(
                summary = "Success".toCaString(),
                completedAt = Clock.System.now(),
                report = object : Operation.Report {
                    override val summary = "Success".toCaString()
                    override val affectedPaths = emptyList<Operation.Report.PathChange>()
                    override val subjectPath = null
                }
            ),
            canCancel = false,
            startedAt = Clock.System.now(),
        ),
    )

    OperationsBar(
        operations = operations,
        onRequestCancelOperation = {},
        onDismissOperation = {},
        onOperationClick = {},
        onClearCompleted = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OperationsBarSingleItemPreview() {
    val singleOperation = OperationDisplay(
        id = Operation.Id(),
        title = "Copying files".toCaString(),
        description = "2 files remaining".toCaString(),
        icon = Icons.TwoTone.Delete,
        state = OperationDisplay.State.Running(
            primaryProgress = Progress.Data(
                primary = "Copying files".toCaString(),
                secondary = "Processing...".toCaString(),
                count = Progress.Count.Percent(8, 10)
            )
        ),
        canCancel = true,
        startedAt = Clock.System.now(),
    )

    PreviewWrapper {
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            OperationsBar(
                operations = listOf(singleOperation),
                onRequestCancelOperation = {},
                onDismissOperation = {},
                onOperationClick = {},
                onClearCompleted = {},
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OperationsBarExpandedPreview() {
    val operations = listOf(
        OperationDisplay(
            id = Operation.Id(),
            title = "Deleting files".toCaString(),
            description = "10 files deleted".toCaString(),
            icon = Icons.TwoTone.Delete,
            state = OperationDisplay.State.Running(
                primaryProgress = Progress.Data(
                    primary = "Deleting files".toCaString(),
                    secondary = "Processing files...".toCaString(),
                    count = Progress.Count.Percent(7, 10)
                )
            ),
            canCancel = true,
            startedAt = Clock.System.now(),
        ),
        OperationDisplay(
            id = Operation.Id(),
            title = "Copy operation".toCaString(),
            description = "100 files copied successfully".toCaString(),
            icon = Icons.TwoTone.Delete,
            state = OperationDisplay.State.Completed(
                summary = "Success".toCaString(),
                completedAt = Clock.System.now(),
                report = object : Operation.Report {
                    override val summary = "Success".toCaString()
                    override val affectedPaths = emptyList<Operation.Report.PathChange>()
                    override val subjectPath = null
                }
            ),
            canCancel = false,
            startedAt = Clock.System.now(),
        ),
        OperationDisplay(
            id = Operation.Id(),
            title = "Move operation".toCaString(),
            description = "Failed to move files".toCaString(),
            icon = Icons.TwoTone.Delete,
            state = OperationDisplay.State.Failed(
                summary = "Permission denied".toCaString(),
                completedAt = Clock.System.now(),
                report = null,
            ),
            canCancel = false,
            startedAt = Clock.System.now(),
        ),
    )

    PreviewWrapper {
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            OperationsBar(
                initialExpanded = true,
                operations = operations,
                onRequestCancelOperation = {},
                onDismissOperation = {},
                onOperationClick = {},
                onClearCompleted = {},
            )
        }
    }
}