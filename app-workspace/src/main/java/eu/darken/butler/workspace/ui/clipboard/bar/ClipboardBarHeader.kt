package eu.darken.butler.workspace.ui.clipboard.bar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ClearAll
import androidx.compose.material.icons.twotone.ExpandLess
import androidx.compose.material.icons.twotone.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.R

@Composable
fun ClipboardBarHeader(
    isExpanded: Boolean,
    entryCount: Int,
    onExpandClick: () -> Unit,
    onClearAllClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        // Title on the left
        Text(
            text = if (entryCount > 1) {
                stringResource(R.string.clipboard_header_title) + " ($entryCount)"
            } else {
                stringResource(R.string.clipboard_header_title)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.align(Alignment.CenterStart),
        )

        // Expand/Collapse button (centered)
        TextButton(
            onClick = onExpandClick,
            modifier = Modifier
                .align(Alignment.Center)
                .height(24.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.TwoTone.ExpandMore else Icons.TwoTone.ExpandLess,
                contentDescription = if (isExpanded) {
                    stringResource(R.string.workspace_expand_less_action)
                } else {
                    stringResource(R.string.workspace_expand_more_action)
                },
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (isExpanded) {
                    stringResource(R.string.workspace_expand_less_action)
                } else {
                    stringResource(R.string.workspace_expand_more_action)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }

        // Clear All button on the right (when multiple entries)
        if (entryCount > 0) {
            TextButton(
                onClick = onClearAllClick,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .height(24.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Icon(
                    imageVector = Icons.TwoTone.ClearAll,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.clipboard_clear_all),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ClipboardBarHeaderPreview() {
    ClipboardBarHeader(
        isExpanded = false,
        entryCount = 3,
        onExpandClick = {},
        onClearAllClick = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ClipboardBarHeaderExpandedPreview() {
    ClipboardBarHeader(
        isExpanded = true,
        entryCount = 3,
        onExpandClick = {},
        onClearAllClick = {},
    )
}