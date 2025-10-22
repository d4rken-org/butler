package eu.darken.butler.workspace.ui.operations.bar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ClearAll
import androidx.compose.material.icons.twotone.ExpandLess
import androidx.compose.material.icons.twotone.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.R

@Composable
fun OperationsBarHeader(
    operationCount: Int,
    completedCount: Int,
    runningCount: Int,
    isExpanded: Boolean,
    onExpandClick: () -> Unit,
    onClearCompleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        // Title on the left
        Text(
            text = if (operationCount > 1) {
                stringResource(R.string.operations_header_title) + " ($operationCount)"
            } else {
                stringResource(R.string.operations_header_title)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.align(Alignment.CenterStart),
        )

        // Expand/Collapse button (centered)
        TextButton(
            onClick = onExpandClick,
            modifier = Modifier
                .align(Alignment.Center)
                .height(24.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.TwoTone.ExpandMore else Icons.TwoTone.ExpandLess,
                contentDescription = if (isExpanded) {
                    stringResource(R.string.operations_show_less)
                } else {
                    stringResource(R.string.operations_show_more)
                },
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (isExpanded) {
                    stringResource(R.string.operations_show_less)
                } else {
                    stringResource(R.string.operations_show_more)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }

        // Clear completed button on the right (when multiple completed operations)
        if (completedCount > 0) {
            TextButton(
                onClick = onClearCompleted,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .height(24.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Icon(
                    imageVector = Icons.TwoTone.ClearAll,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.operations_clear_completed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Preview2
@Composable
private fun OperationsBarHeaderPreview() {
    PreviewWrapper {
        OperationsBarHeader(
            operationCount = 3,
            completedCount = 1,
            runningCount = 2,
            isExpanded = false,
            onExpandClick = {},
            onClearCompleted = {},
        )
    }
}

@Preview2
@Composable
private fun OperationsBarHeaderExpandedPreview() {
    PreviewWrapper {
        OperationsBarHeader(
            operationCount = 3,
            completedCount = 1,
            runningCount = 2,
            isExpanded = true,
            onExpandClick = {},
            onClearCompleted = {},
        )
    }
}