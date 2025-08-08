package eu.darken.butler.workspace.ui.workspaces.adaptive

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material.icons.twotone.Workspaces
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.darken.butler.workspace.core.Workspace
import kotlin.math.roundToInt

@Composable
fun DragPreview(
    workspace: Workspace.Info?,
    dragPosition: Offset,
    modifier: Modifier = Modifier,
) {
    if (workspace == null) return

    LocalDensity.current
    val scale by animateFloatAsState(
        targetValue = if (workspace != null) 1.2f else 1f,
        label = "drag_scale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (workspace != null) 0.9f else 0f,
        label = "drag_alpha"
    )

    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    dragPosition.x.roundToInt(),
                    dragPosition.y.roundToInt()
                )
            }
            .scale(scale)
            .alpha(alpha)
    ) {
        Card(
            modifier = Modifier.size(64.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when (workspace.type) {
                        Workspace.Type.TEMPLATES -> Icons.TwoTone.Workspaces
                        Workspace.Type.EXPLORER -> Icons.TwoTone.Folder
                        Workspace.Type.SEARCHER -> Icons.TwoTone.Search
                        Workspace.Type.EDITOR -> Icons.TwoTone.Edit
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}