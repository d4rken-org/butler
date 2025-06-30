package eu.darken.butler.workspace.ui.manager.rows

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

@Composable
fun WorkspaceStatusCard(
    workspaceCount: Int,
    operationsCount: Int,
    attentionCount: Int,
    modifier: Modifier = Modifier
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
                label = if (workspaceCount == 1) "Workspace" else "Workspaces",
                isActive = true,
                isPrimary = true
            )

            // Operations count
            StatusItem(
                count = operationsCount,
                label = if (operationsCount == 1) "Operation" else "Operations",
                isActive = operationsCount > 0,
                isPrimary = true
            )

            // Attention count
            StatusItem(
                count = attentionCount,
                label = "Attention",
                isActive = attentionCount > 0,
                isPrimary = false
            )
        }
    }
}

@Composable
private fun StatusItem(
    count: Int,
    label: String,
    isActive: Boolean,
    isPrimary: Boolean
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
                        !isActive -> MaterialTheme.colorScheme.surfaceVariant
                        isPrimary -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.errorContainer
                    },
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (count > 9) "9+" else count.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    !isActive -> MaterialTheme.colorScheme.onSurfaceVariant
                    isPrimary -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.error
                }
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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