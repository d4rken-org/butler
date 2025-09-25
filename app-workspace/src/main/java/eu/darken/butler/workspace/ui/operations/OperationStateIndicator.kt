package eu.darken.butler.workspace.ui.operations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Cancel
import androidx.compose.material.icons.twotone.CheckCircle
import androidx.compose.material.icons.twotone.Error
import androidx.compose.material.icons.twotone.Pause
import androidx.compose.material.icons.twotone.PauseCircle
import androidx.compose.material3.Icon
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
import kotlin.time.Clock

@Composable
fun OperationStateIndicator(
    state: OperationDisplay.State,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is OperationDisplay.State.Queued -> {
            Icon(
                imageVector = Icons.TwoTone.PauseCircle,
                contentDescription = stringResource(R.string.operations_state_queued),
                modifier = modifier,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is OperationDisplay.State.Running -> {
            Icon(
                imageVector = Icons.TwoTone.PauseCircle,
                contentDescription = stringResource(R.string.operations_state_running),
                modifier = modifier,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        is OperationDisplay.State.Waiting -> {
            Icon(
                imageVector = Icons.TwoTone.Pause,
                contentDescription = stringResource(R.string.operations_state_waiting),
                modifier = modifier,
                tint = MaterialTheme.colorScheme.tertiary,
            )
        }
        is OperationDisplay.State.Completed -> {
            Icon(
                imageVector = Icons.TwoTone.CheckCircle,
                contentDescription = stringResource(R.string.operations_state_completed),
                modifier = modifier,
                tint = Color(0xFF4CAF50), // Success green
            )
        }
        is OperationDisplay.State.Failed -> {
            Icon(
                imageVector = Icons.TwoTone.Error,
                contentDescription = stringResource(R.string.operations_state_failed),
                modifier = modifier,
                tint = MaterialTheme.colorScheme.error,
            )
        }
        is OperationDisplay.State.Cancelled -> {
            Icon(
                imageVector = Icons.TwoTone.Cancel,
                contentDescription = stringResource(R.string.operations_state_cancelled),
                modifier = modifier,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview2
@Composable
private fun OperationStateIndicatorPreview() {
    PreviewWrapper {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OperationStateIndicator(
                state = OperationDisplay.State.Queued,
                modifier = Modifier.size(24.dp)
            )
            OperationStateIndicator(
                state = OperationDisplay.State.Running(),
                modifier = Modifier.size(24.dp)
            )
            OperationStateIndicator(
                state = OperationDisplay.State.Completed(
                    summary = "Done".toCaString(),
                    completedAt = Clock.System.now()
                ),
                modifier = Modifier.size(24.dp)
            )
            OperationStateIndicator(
                state = OperationDisplay.State.Failed(
                    summary = "Error".toCaString(),
                    completedAt = Clock.System.now()
                ),
                modifier = Modifier.size(24.dp)
            )
            OperationStateIndicator(
                state = OperationDisplay.State.Cancelled(
                    completedAt = Clock.System.now()
                ),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}