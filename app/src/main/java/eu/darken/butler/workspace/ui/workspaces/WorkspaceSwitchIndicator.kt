package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.delay

@Composable
fun WorkspaceSwitchIndicator(
    modifier: Modifier = Modifier,
    position: Int,
    totalWorkspaces: Int,
    workspaceName: String,
    workspaceId: Workspace.Id,
) {
    // Reset visibility state when workspace ID changes
    var isVisible by remember(key1 = workspaceId) { mutableStateOf(true) }

    // Only trigger on workspace ID change, not on name change
    LaunchedEffect(workspaceId) {
        isVisible = true
        delay(700)
        isVisible = false
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(
            animationSpec = tween(durationMillis = 50)
        ) + scaleIn(
            initialScale = 0.8f,
            animationSpec = tween(durationMillis = 50)
        ),
        exit = fadeOut(
            animationSpec = tween(durationMillis = 100)
        ) + scaleOut(
            targetScale = 0.9f,
            animationSpec = tween(durationMillis = 100)
        ),
        modifier = modifier,
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = 8.dp
            ),
        ) {
            Text(
                text = stringResource(
                    id = eu.darken.butler.common.R.string.common_workspace_position_indicator,
                    position,
                    totalWorkspaces,
                    workspaceName
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
    }
}

@Preview2
@Composable
private fun WorkspaceSwitchIndicatorPreview() {
    PreviewWrapper {
        WorkspaceSwitchIndicator(
            position = 2,
            totalWorkspaces = 5,
            workspaceName = "Explorer",
            workspaceId = Workspace.Id(),
        )
    }
}

@Preview2
@Composable
private fun WorkspaceSwitchIndicatorLongNamePreview() {
    PreviewWrapper {
        WorkspaceSwitchIndicator(
            position = 3,
            totalWorkspaces = 10,
            workspaceName = "Very Long Workspace Name",
            workspaceId = Workspace.Id(),
        )
    }
}
