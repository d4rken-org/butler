package eu.darken.butler.workspace.ui.manager.rows

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

/**
 * Marks a card whose tab carries stacked sub-workspaces. Deliberately the same shape, size and
 * colour role as [PaneBadge] on the opposite corner, so both read as one badge system.
 */
@Composable
fun StackBadge(
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(18.dp),
        color = MaterialTheme.colorScheme.primary,
        shape = CircleShape,
    ) {
        Box(
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                modifier = Modifier.size(12.dp),
                imageVector = Icons.TwoTone.Layers,
                contentDescription = stringResource(R.string.workspace_row_stacked_content_desc),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun StackBadgePreview() {
    StackBadge()
}
