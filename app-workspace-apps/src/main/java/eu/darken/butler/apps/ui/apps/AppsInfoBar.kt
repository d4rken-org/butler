package eu.darken.butler.apps.ui.apps

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Android
import androidx.compose.material.icons.twotone.CheckBox
import androidx.compose.material.icons.twotone.Person
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
import eu.darken.butler.apps.R
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

@Composable
fun AppsInfoBar(
    modifier: Modifier = Modifier,
    userAppsCount: Int = 0,
    systemAppsCount: Int = 0,
    selectedCount: Int = 0,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Selection chip (always first when present)
        if (selectedCount > 0) {
            InfoChip(
                icon = Icons.TwoTone.CheckBox,
                label = pluralStringResource(R.plurals.apps_infobar_selected_count, selectedCount, selectedCount),
                isAccented = true,
            )
        }

        // User apps count
        if (selectedCount == 0 && userAppsCount > 0) {
            InfoChip(
                icon = Icons.TwoTone.Person,
                label = pluralStringResource(R.plurals.apps_infobar_user_apps_count, userAppsCount, userAppsCount),
            )
        }

        // System apps count
        if (selectedCount == 0 && systemAppsCount > 0) {
            InfoChip(
                icon = Icons.TwoTone.Android,
                label = pluralStringResource(
                    R.plurals.apps_infobar_system_apps_count,
                    systemAppsCount,
                    systemAppsCount
                ),
            )
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun InfoChip(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    isAccented: Boolean = false,
) {
    AssistChip(
        onClick = { /* Could be expandable in future */ },
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
        modifier = modifier.height(24.dp),
        border = null,
        colors = if (isAccented) {
            AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.primary,
                labelColor = MaterialTheme.colorScheme.onPrimary,
                leadingIconContentColor = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    )
}

@Preview2
@Composable
private fun AppsInfoBarPreview() {
    PreviewWrapper {
        AppsInfoBar(
            userAppsCount = 25,
            systemAppsCount = 142,
            selectedCount = 0,
        )
    }
}

@Preview2
@Composable
private fun AppsInfoBarWithSelectionPreview() {
    PreviewWrapper {
        AppsInfoBar(
            userAppsCount = 25,
            systemAppsCount = 142,
            selectedCount = 5,
        )
    }
}

@Preview2
@Composable
private fun AppsInfoBarEmptyPreview() {
    PreviewWrapper {
        AppsInfoBar(
            userAppsCount = 0,
            systemAppsCount = 0,
            selectedCount = 0,
        )
    }
}
