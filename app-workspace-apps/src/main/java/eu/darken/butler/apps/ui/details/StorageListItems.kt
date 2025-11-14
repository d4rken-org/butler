package eu.darken.butler.apps.ui.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.FolderOpen
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.darken.butler.apps.core.AppPath
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.APath

@Composable
fun StorageListItems(
    modifier: Modifier = Modifier,
    availablePaths: List<AppPath>,
    onBrowsePath: (APath<*>) -> Unit,
) {
    if (availablePaths.isEmpty()) return

    val context = LocalContext.current

    Column(modifier = modifier.fillMaxWidth()) {
        availablePaths.forEachIndexed { index, appPath ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onBrowsePath(appPath.path) }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.TwoTone.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = appPath.label.get(context),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = appPath.path.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (index < availablePaths.size - 1) {
                HorizontalDivider()
            }
        }
    }
}

@Preview2
@Composable
private fun StorageListItemsPreview() {
    PreviewWrapper {
        StorageListItems(
            availablePaths = emptyList(),
            onBrowsePath = {}
        )
    }
}
