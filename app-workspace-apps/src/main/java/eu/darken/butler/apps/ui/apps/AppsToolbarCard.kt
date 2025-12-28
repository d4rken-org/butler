package eu.darken.butler.apps.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign

@Composable
fun AppsToolbarCard(
    modifier: Modifier = Modifier,
    workspaceId: Workspace.Id,
    searchQuery: TextFieldValue,
    onSearchQueryChange: (TextFieldValue) -> Unit,
    design: WorkspaceDesign,
    workspaceButtonState: WorkspaceButtonViewModel.State?,
    workspaceActionHandler: WorkspaceActionHandler?,
    collapsedFraction: Float = 0f,
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
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppsSearchBar(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                modifier = Modifier.weight(1f)
            )

            if (design.isSingle) {
                WorkspaceButton(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .graphicsLayer { alpha = 1f - collapsedFraction },
                    buttonSize = 32.dp,
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
            searchQuery = TextFieldValue(""),
            onSearchQueryChange = {},
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
            searchQuery = TextFieldValue("Chrome"),
            onSearchQueryChange = {},
            design = WorkspaceDesign(),
            workspaceButtonState = null,
            workspaceActionHandler = null,
            modifier = Modifier.padding(16.dp)
        )
    }
}
