package eu.darken.butler.workspace.ui.operations.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Share
import androidx.compose.material.icons.twotone.Handyman
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.ui.operations.OperationDisplay

@Composable
internal fun OperationActionsSection(
    operation: OperationDisplay,
    onCancel: (() -> Unit)? = null,
    onShareError: (() -> Unit)? = null,
    onHandleIssue: (() -> Unit)? = null,
    onShowInHistory: (() -> Unit)? = null,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Section title
            Text(
                text = stringResource(R.string.operations_details_actions).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )

            when (operation.state) {
                is OperationDisplay.State.Running -> if (onCancel != null) {
                    ActionButton(
                        icon = Icons.TwoTone.Close,
                        label = stringResource(R.string.operations_cancel_operation),
                        onClick = onCancel,
                    )
                }
                is OperationDisplay.State.Failed -> if (onShareError != null) {
                    ActionButton(
                        icon = Icons.TwoTone.Share,
                        label = stringResource(eu.darken.butler.common.R.string.general_share_error_action),
                        onClick = onShareError,
                    )
                }
                is OperationDisplay.State.Waiting -> if (onHandleIssue != null) {
                    ActionButton(
                        icon = Icons.TwoTone.Handyman,
                        label = stringResource(R.string.operations_details_handle_issue),
                        onClick = onHandleIssue,
                    )
                }
                else -> Unit
            }

            if (onShowInHistory != null) {
                ActionButton(
                    icon = Workspace.Type.HISTORY.icon,
                    label = stringResource(R.string.operations_details_show_in_history_action),
                    onClick = onShowInHistory,
                )
            }
        }
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(label)
    }
}
