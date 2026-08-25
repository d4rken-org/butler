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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import kotlin.time.Clock

internal data class OperationStateVisuals(
    val imageVector: ImageVector,
    val contentDescription: String,
    val tint: Color,
)

@Composable
internal fun operationStateVisuals(state: OperationDisplay.State): OperationStateVisuals = when (state) {
    is OperationDisplay.State.Queued -> OperationStateVisuals(
        imageVector = Icons.TwoTone.PauseCircle,
        contentDescription = stringResource(R.string.operations_state_queued),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    is OperationDisplay.State.Running -> OperationStateVisuals(
        imageVector = Icons.TwoTone.PauseCircle,
        contentDescription = stringResource(R.string.operations_state_running),
        tint = MaterialTheme.colorScheme.primary,
    )
    is OperationDisplay.State.Waiting -> OperationStateVisuals(
        imageVector = Icons.TwoTone.Handyman,
        contentDescription = stringResource(R.string.operations_state_waiting),
        tint = MaterialTheme.colorScheme.tertiary,
    )
    is OperationDisplay.State.Completed -> OperationStateVisuals(
        imageVector = Icons.TwoTone.CheckCircle,
        contentDescription = stringResource(R.string.operations_state_successful),
        tint = Color(0xFF4CAF50), // Success green
    )
    is OperationDisplay.State.Failed -> OperationStateVisuals(
        imageVector = Icons.TwoTone.Error,
        contentDescription = stringResource(R.string.operations_state_failed),
        tint = MaterialTheme.colorScheme.error,
    )
    is OperationDisplay.State.Cancelled -> OperationStateVisuals(
        imageVector = Icons.TwoTone.Cancel,
        contentDescription = stringResource(R.string.operations_state_cancelled),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

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
        val visuals = when {
            isActionable -> OperationStateVisuals(
                imageVector = Icons.TwoTone.Cancel,
                contentDescription = stringResource(R.string.operations_cancel_operation),
                tint = MaterialTheme.colorScheme.error,
            )
            else -> operationStateVisuals(state)
        }

        Icon(
            imageVector = visuals.imageVector,
            contentDescription = visuals.contentDescription,
            modifier = modifier,
            tint = visuals.tint,
        )
    }

}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OperationActionIndicatorPreview() {
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