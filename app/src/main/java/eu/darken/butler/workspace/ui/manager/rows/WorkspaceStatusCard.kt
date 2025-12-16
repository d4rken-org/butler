package eu.darken.butler.workspace.ui.manager.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

@Composable
fun WorkspaceStatusCard(
    workspaceCount: Int,
    operationsCount: Int,
    attentionCount: Int,
    modifier: Modifier = Modifier,
    isOperationsFilterActive: Boolean = false,
    isAttentionFilterActive: Boolean = false,
    onTabsClick: () -> Unit = {},
    onOperationsClick: () -> Unit = {},
    onAttentionClick: () -> Unit = {},
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Workspace count
            StatusItem(
                count = workspaceCount,
                label = if (workspaceCount == 1) stringResource(R.string.workspace_status_tab_singular) else stringResource(
                    R.string.workspace_status_tab_plural
                ),
                isActive = true,
                isPrimary = true,
                isFilterActive = false,
                onClick = onTabsClick,
                contentDesc = stringResource(R.string.workspace_status_filter_tabs_desc),
            )

            // Operations count
            StatusItem(
                count = operationsCount,
                label = if (operationsCount == 1) stringResource(R.string.workspace_status_operation_singular) else stringResource(
                    R.string.workspace_status_operation_plural
                ),
                isActive = operationsCount > 0,
                isPrimary = true,
                isFilterActive = isOperationsFilterActive,
                onClick = onOperationsClick,
                contentDesc = stringResource(R.string.workspace_status_filter_operations_desc),
            )

            // Attention count
            StatusItem(
                count = attentionCount,
                label = stringResource(R.string.workspace_status_attention_label),
                isActive = attentionCount > 0,
                isPrimary = false,
                isFilterActive = isAttentionFilterActive,
                onClick = onAttentionClick,
                contentDesc = stringResource(R.string.workspace_status_filter_attention_desc),
            )
        }
    }
}

@Composable
private fun StatusItem(
    count: Int,
    label: String,
    isActive: Boolean,
    isPrimary: Boolean,
    isFilterActive: Boolean = false,
    onClick: () -> Unit = {},
    contentDesc: String? = null,
) {
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .then(
                if (contentDesc != null) {
                    Modifier.semantics { contentDescription = contentDesc }
                } else {
                    Modifier
                }
            )
            .background(
                color = if (isFilterActive) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color.Transparent
                },
                shape = RoundedCornerShape(12.dp)
            )
            .border(
                width = 2.dp,
                color = if (isFilterActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.Transparent
                },
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        color = when {
                            isFilterActive -> MaterialTheme.colorScheme.primary
                            !isActive -> MaterialTheme.colorScheme.surfaceVariant
                            isPrimary -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.errorContainer
                        },
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        isFilterActive -> MaterialTheme.colorScheme.onPrimary
                        !isActive -> MaterialTheme.colorScheme.onSurfaceVariant
                        isPrimary -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.error
                    }
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (isFilterActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Preview2
@Composable
private fun WorkspaceStatusCardPreview() {
    PreviewWrapper {
        WorkspaceStatusCard(
            workspaceCount = 5,
            operationsCount = 3,
            attentionCount = 2
        )
    }
}

@Preview2
@Composable
private fun WorkspaceStatusCardEmptyPreview() {
    PreviewWrapper {
        WorkspaceStatusCard(
            workspaceCount = 1,
            operationsCount = 0,
            attentionCount = 0
        )
    }
}