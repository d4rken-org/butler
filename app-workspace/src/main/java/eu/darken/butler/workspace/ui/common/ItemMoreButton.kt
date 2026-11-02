package eu.darken.butler.workspace.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.workspace.R

/**
 * Opens a file's actions sheet while a tap on the file opens it. A [size] shrinks the button, touch
 * target included, so it fits a row or tile without growing it; null keeps Material's 48dp button.
 */
@Composable
fun ItemMoreButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    contentDescription: String,
    size: Dp? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    CompositionLocalProvider(
        LocalMinimumInteractiveComponentSize provides if (size != null) {
            Dp.Unspecified
        } else {
            LocalMinimumInteractiveComponentSize.current
        },
    ) {
        IconButton(
            onClick = onClick,
            modifier = if (size != null) modifier.size(size) else modifier,
        ) {
            Icon(
                imageVector = Icons.TwoTone.MoreVert,
                contentDescription = contentDescription,
                tint = tint,
                modifier = if (size != null) Modifier.size(size.coerceAtMost(24.dp)) else Modifier,
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ItemMoreButtonPreview() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val label = stringResource(R.string.workspace_action_more)
        ItemMoreButton(onClick = {}, contentDescription = label, size = 16.dp)
        ItemMoreButton(onClick = {}, contentDescription = label, size = 24.dp)
        ItemMoreButton(onClick = {}, contentDescription = label, size = 32.dp)
        ItemMoreButton(onClick = {}, contentDescription = label)
    }
}
