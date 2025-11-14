package eu.darken.butler.workspace.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.CheckBox
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.common.R
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

@Composable
fun WorkspaceInfoBar(
    modifier: Modifier = Modifier,
    selectedCount: Int = 0,
    onClearSelection: (() -> Unit)? = null,
    leadingContent: @Composable RowScope.() -> Unit = {},
    trailingContent: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectedCount > 0) {
            InfoChip(
                icon = Icons.TwoTone.CheckBox,
                label = pluralStringResource(R.plurals.common_infobar_selected_count, selectedCount, selectedCount),
                isAccented = true,
                onClick = onClearSelection,
                trailingIcon = if (onClearSelection != null) Icons.TwoTone.Close else null,
            )
        }

        leadingContent()

        trailingContent()
    }
}

@Composable
fun InfoChip(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    isAccented: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailingIcon: ImageVector? = null,
) {
    AssistChip(
        onClick = onClick ?: {},
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
        },
        trailingIcon = trailingIcon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
            }
        },
        modifier = modifier.height(24.dp),
        border = null,
        colors = if (isAccented) {
            AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.primary,
                labelColor = MaterialTheme.colorScheme.onPrimary,
                leadingIconContentColor = MaterialTheme.colorScheme.onPrimary,
                trailingIconContentColor = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                trailingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    )
}

@Preview2
@Composable
private fun WorkspaceInfoBarWithSelectionPreview() {
    PreviewWrapper {
        WorkspaceInfoBar(
            selectedCount = 3,
            onClearSelection = {},
        )
    }
}

@Preview2
@Composable
private fun WorkspaceInfoBarWithoutClearPreview() {
    PreviewWrapper {
        WorkspaceInfoBar(
            selectedCount = 5,
        )
    }
}

@Preview2
@Composable
private fun WorkspaceInfoBarWithContentPreview() {
    PreviewWrapper {
        WorkspaceInfoBar(
            selectedCount = 0,
            leadingContent = {
                InfoChip(
                    icon = Icons.TwoTone.CheckBox,
                    label = "42 items"
                )
                InfoChip(
                    icon = Icons.TwoTone.CheckBox,
                    label = "7 folders"
                )
            },
            trailingContent = {
                Spacer(modifier = Modifier.weight(1f))
                InfoChip(
                    icon = Icons.TwoTone.CheckBox,
                    label = "512 MB"
                )
            }
        )
    }
}
