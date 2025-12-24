package eu.darken.butler.workspace.ui.operations.bar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Cancel
import androidx.compose.material.icons.twotone.CheckCircle
import androidx.compose.material.icons.twotone.Error
import androidx.compose.material.icons.twotone.PauseCircle
import androidx.compose.material.icons.twotone.Handyman
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import kotlin.time.Clock

@Composable
fun OperationActionIndicator(
    modifier: Modifier = Modifier,
    state: OperationDisplay.State,
    onAction: (() -> Unit)? = null,
    onFallbackClick: (() -> Unit)? = null,
) {
    val isActionable = state is OperationDisplay.State.Running && onAction != null
    val effectiveOnClick = onAction ?: onFallbackClick

    IconButton(
        modifier = modifier,
        enabled = effectiveOnClick != null,
        onClick = effectiveOnClick ?: {},
    ) {
        val (imageVector, contentDescriptionRes, tint) = when (state) {
            is OperationDisplay.State.Queued -> Triple(
                Icons.TwoTone.PauseCircle,
                R.string.operations_state_queued,
                MaterialTheme.colorScheme.onSurfaceVariant
            )
            is OperationDisplay.State.Running -> when {
                isActionable -> Triple(
                    Icons.TwoTone.Cancel,
                    R.string.operations_cancel_operation,
                    MaterialTheme.colorScheme.error
                )
                else -> Triple(
                    Icons.TwoTone.PauseCircle,
                    R.string.operations_state_running,
                    MaterialTheme.colorScheme.primary
                )
            }
            is OperationDisplay.State.Waiting -> Triple(
                Icons.TwoTone.Handyman,
                R.string.operations_state_waiting,
                MaterialTheme.colorScheme.tertiary
            )
            is OperationDisplay.State.Completed -> Triple(
                Icons.TwoTone.CheckCircle,
                R.string.operations_state_successful,
                Color(0xFF4CAF50) // Success green
            )
            is OperationDisplay.State.Failed -> Triple(
                Icons.TwoTone.Error,
                R.string.operations_state_failed,
                MaterialTheme.colorScheme.error
            )
            is OperationDisplay.State.Cancelled -> Triple(
                Icons.TwoTone.Cancel,
                R.string.operations_state_cancelled,
                MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Icon(
            imageVector = imageVector,
            contentDescription = stringResource(contentDescriptionRes),
            modifier = modifier,
            tint = tint,
        )
    }

}

@Preview2
@Composable
private fun OperationActionIndicatorPreview() {
    PreviewWrapper {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OperationActionIndicator(
                state = OperationDisplay.State.Queued,
                modifier = Modifier.size(24.dp)
            )
            OperationActionIndicator(
                state = OperationDisplay.State.Running(),
                modifier = Modifier.size(24.dp)
            )
            OperationActionIndicator(
                state = OperationDisplay.State.Running(),
                modifier = Modifier.size(24.dp),
                onAction = {} // Shows cancel button
            )
            OperationActionIndicator(
                state = OperationDisplay.State.Completed(
                    summary = "Done".toCaString(),
                    completedAt = Clock.System.now(),
                    report = object : Operation.Report {
                        override val summary = "Done".toCaString()
                        override val affectedPaths = emptyList<Operation.Report.PathChange>()
                    }
                ),
                modifier = Modifier.size(24.dp)
            )
            OperationActionIndicator(
                state = OperationDisplay.State.Failed(
                    summary = "Error".toCaString(),
                    completedAt = Clock.System.now(),
                    report = object : Operation.Report {
                        override val summary = "Error".toCaString()
                        override val affectedPaths = emptyList<Operation.Report.PathChange>()
                    }
                ),
                modifier = Modifier.size(24.dp)
            )
            OperationActionIndicator(
                state = OperationDisplay.State.Cancelled(
                    completedAt = Clock.System.now(),
                    report = object : Operation.Report {
                        override val summary = "Cancelled".toCaString()
                        override val affectedPaths = emptyList<Operation.Report.PathChange>()
                    }
                ),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}