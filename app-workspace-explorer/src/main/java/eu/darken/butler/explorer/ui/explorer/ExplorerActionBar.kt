package eu.darken.butler.explorer.ui.explorer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.explorer.ui.explorer.actions.ExplorerAction

@Composable
fun ExplorerActionBar(
    modifier: Modifier = Modifier,
    actions: List<ExplorerAction>,
    onActionClick: (ExplorerAction) -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        val visibleActions = actions.filter { 
            it.isVisible && it.group != ExplorerAction.Group.SELECTION_INFO 
        }
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            visibleActions.forEach { action ->
                IconButton(
                    onClick = { onActionClick(action) },
                    enabled = action.isEnabled,
                ) {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = action.label,
                        tint = when {
                            !action.isEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            action.isDestructive -> MaterialTheme.colorScheme.error
                            else -> LocalContentColor.current
                        }
                    )
                }
            }
        }
    }
}

@Preview2
@Composable
fun ExplorerBottomBarNormalModePreview() {
    val mockActions = listOf(
        ExplorerAction.Directory.CreateFolder(),
        ExplorerAction.Common.Sort(),
        ExplorerAction.Common.Filter(),
        ExplorerAction.Common.ToggleView(),
        ExplorerAction.Common.More(),
    )
    
    PreviewWrapper {
        ExplorerActionBar(
            actions = mockActions,
            onActionClick = {},
        )
    }
}

@Preview2
@Composable
fun ExplorerBottomBarSelectionModePreview() {
    val mockActions = listOf(
        ExplorerAction.Directory.SelectionInfo(3),
        ExplorerAction.Directory.Copy(),
        ExplorerAction.Directory.Cut(),
        ExplorerAction.Directory.Delete(),
        ExplorerAction.Directory.Share(),
        ExplorerAction.Common.More(),
    )
    
    PreviewWrapper {
        ExplorerActionBar(
            actions = mockActions,
            onActionClick = {},
        )
    }
}

@Preview2
@Composable
fun ExplorerBottomBarDisabledActionsPreview() {
    val mockActions = listOf(
        ExplorerAction.Directory.CreateFolder(isEnabled = false),
        ExplorerAction.Common.Sort(),
        ExplorerAction.Common.Filter(isEnabled = false),
        ExplorerAction.Common.More(),
    )
    
    PreviewWrapper {
        ExplorerActionBar(
            actions = mockActions,
            onActionClick = {},
        )
    }
}