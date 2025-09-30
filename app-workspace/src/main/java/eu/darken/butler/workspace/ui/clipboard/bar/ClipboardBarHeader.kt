package eu.darken.butler.workspace.ui.clipboard.bar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
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
    if (isExpanded) {
        // Expanded mode: Two buttons spanning full width
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Collapse button (extends to meet clear all button)
            TextButton(
                onClick = onExpandClick,
                modifier = Modifier
                    .height(32.dp)
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Icon(
                    imageVector = Icons.TwoTone.ExpandLess,
                    contentDescription = stringResource(R.string.clipboard_show_less),
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.clipboard_show_less),
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Clear All button
            TextButton(
                onClick = onClearAllClick,
                modifier = Modifier
                    .height(32.dp)
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
            ) {
                Icon(
                    imageVector = Icons.TwoTone.ClearAll,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.clipboard_clear_all),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    } else {
        // Collapsed mode: Single expand button fills full width
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onExpandClick,
                modifier = Modifier
                    .height(32.dp)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Icon(
                    imageVector = Icons.TwoTone.ExpandMore,
                    contentDescription = stringResource(R.string.clipboard_show_more),
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.clipboard_show_more_items, entryCount),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Preview2
@Composable
private fun ClipboardBarHeaderPreview() {
    PreviewWrapper {
        ClipboardBarHeader(
            isExpanded = false,
            entryCount = 3,
            onExpandClick = {},
            onClearAllClick = {},
        )
    }
}