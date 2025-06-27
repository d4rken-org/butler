package eu.darken.butler.workspace.ui.manager

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.AddCircle
import androidx.compose.material.icons.twotone.Workspaces
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.butler.workspace.core.WorkspaceAction

@Composable
fun WorkspaceButton(
    modifier: Modifier = Modifier,
    state: WorkspaceButtonViewModel.State?,
    onAction: (WorkspaceAction) -> Unit,
    onNavToWorkspaceManager: () -> Unit,
) {
    val (normalAction, longAction) = if (state?.isButtonFlipped == true) {
        // Flipped mode: normal click adds workspace, long click opens manager
        { onAction(WorkspaceAction.Create()) } to { onNavToWorkspaceManager() }
    } else {
        // Normal mode: normal click opens manager, long click adds workspace
        { onNavToWorkspaceManager() } to { onAction(WorkspaceAction.Create()) }
    }

    val icon = if (state?.isButtonFlipped == true) {
        Icons.TwoTone.AddCircle
    } else {
        Icons.TwoTone.Workspaces
    }

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.95f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        modifier = modifier.combinedClickable(
            onClick = normalAction,
            onLongClick = longAction
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(12.dp)
        )
    }
}