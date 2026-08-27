package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.FolderShared
import androidx.compose.material.icons.twotone.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.storage.saf.KnownStorageProvider
import eu.darken.butler.common.storage.saf.StorageProviderSuggestion
import eu.darken.butler.explorer.R
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet

@Composable
fun AddDeviceStorageSheet(
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
    suggestions: List<StorageProviderSuggestion> = emptyList(),
    onSuggestion: (StorageProviderSuggestion) -> Unit = {},
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
) {
    PaneScopedBottomSheet(
        visible = true,
        onDismiss = onDismiss,
        topInset = topInset,
        bottomInset = bottomInset,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Text(
                text = stringResource(R.string.explorer_add_device_storage_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.FolderShared,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Text(
                    text = stringResource(R.string.explorer_add_device_storage_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }

            if (suggestions.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.explorer_add_device_storage_suggestions_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )

                suggestions.forEach { suggestion ->
                    SuggestionRow(
                        suggestion = suggestion,
                        onClick = {
                            onDismiss()
                            onSuggestion(suggestion)
                        },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.explorer_add_device_storage_cancel))
                }

                Button(onClick = {
                    onDismiss()
                    onContinue()
                }) {
                    Text(stringResource(R.string.explorer_add_device_storage_continue))
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    modifier: Modifier = Modifier,
    suggestion: StorageProviderSuggestion,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when {
                    suggestion.known != null -> Icons.TwoTone.Terminal
                    else -> Icons.TwoTone.FolderShared
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(22.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = suggestion.label,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                // Only a curated provider gets navigated to, the generic path just opens the picker
                text = when {
                    suggestion.known != null -> stringResource(R.string.explorer_add_device_storage_suggestion_desc_direct)
                    else -> stringResource(R.string.explorer_add_device_storage_suggestion_desc_pick)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AddDeviceStorageSheetPreview() {
    AddDeviceStorageSheet(
        onDismiss = {},
        onContinue = {}
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AddDeviceStorageSheetSuggestionsPreview() {
    AddDeviceStorageSheet(
        onDismiss = {},
        onContinue = {},
        suggestions = listOf(
            StorageProviderSuggestion(
                packageName = "com.termux",
                authority = "com.termux.documents",
                label = "Termux",
                known = KnownStorageProvider.TERMUX,
            ),
            StorageProviderSuggestion(
                packageName = "com.mixplorer",
                authority = "com.mixplorer.documents",
                label = "MiXplorer",
                known = null,
            ),
        ),
    )
}
