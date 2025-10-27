package eu.darken.butler.apps.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.apps.R
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign

@Composable
fun AppsToolbarCard(
    workspaceId: Workspace.Id,
    modifier: Modifier = Modifier,
    searchQuery: String,
    design: WorkspaceDesign,
    workspaceButtonState: WorkspaceButtonViewModel.State?,
    workspaceActionHandler: WorkspaceActionHandler?,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                modifier = Modifier.size(24.dp),
                imageVector = Icons.TwoTone.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )

            Text(
                text = searchQuery.ifBlank { stringResource(R.string.apps_search_hint) },
                style = MaterialTheme.typography.bodyMedium,
                color = if (searchQuery.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            if (design.isSingle) {
                WorkspaceButton(
                    modifier = Modifier.size(32.dp),
                    state = workspaceButtonState,
                    currentWorkspaceId = workspaceId,
                    workspaceActionHandler = workspaceActionHandler,
                )
            }
        }
    }
}

@Preview2
@Composable
private fun AppsToolbarCardPreview() {
    PreviewWrapper {
        AppsToolbarCard(
            workspaceId = Workspace.Id(),
            searchQuery = "",
            design = WorkspaceDesign(),
            workspaceButtonState = null,
            workspaceActionHandler = null,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview2
@Composable
private fun AppsToolbarCardWithQueryPreview() {
    PreviewWrapper {
        AppsToolbarCard(
            workspaceId = Workspace.Id(),
            searchQuery = "Chrome",
            design = WorkspaceDesign(),
            workspaceButtonState = null,
            workspaceActionHandler = null,
            modifier = Modifier.padding(16.dp)
        )
    }
}
