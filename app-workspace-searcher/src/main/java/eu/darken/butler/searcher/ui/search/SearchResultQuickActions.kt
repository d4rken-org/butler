package eu.darken.butler.searcher.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.TintedAsyncImage
import eu.darken.butler.common.formatDate
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet

@Composable
fun SearchResultQuickActions(
    result: SearchItem,
    onAction: (SearcherAction) -> Unit,
    onLongPress: (SearchItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    PaneScopedBottomSheet(
        visible = true,
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            // Header with file info
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Preview/Icon
                TintedAsyncImage(
                    model = result.lookup,
                    contentDescription = result.fileType.name,
                    modifier = Modifier.size(40.dp)
                )

                Spacer(modifier = Modifier.width(16.dp))

                // Title and subtitle
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = result.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // Size and modification time
                    val metadataText = buildString {
                        result.lookup.size?.let { append(formatFileSize(it)) } ?: append("?")
                        append(" • ")
                        result.lookup.modifiedAt?.let { append(formatDate(it)) } ?: append("?")
                    }
                    Text(
                        text = metadataText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = result.path.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            Column(
                modifier = Modifier.padding(top = 4.dp)
            ) {
                // Primary actions
                if (isTextFile(result)) {
                    QuickActionItem(
                        action = SearcherAction.OpenInEditor(result),
                        onClick = onAction,
                        isPrimary = true
                    )
                }

                QuickActionItem(
                    action = SearcherAction.OpenInExplorer(result),
                    onClick = onAction,
                    isPrimary = true
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                // Clipboard actions
                QuickActionItem(
                    action = SearcherAction.Copy(listOf(result)),
                    onClick = onAction
                )

                QuickActionItem(
                    action = SearcherAction.Cut(listOf(result)),
                    onClick = onAction
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                // Additional actions
                QuickActionItem(
                    action = SearcherAction.Share(listOf(result)),
                    onClick = onAction
                )

                QuickActionItem(
                    action = SearcherAction.CopyPath(result),
                    onClick = onAction
                )

                QuickActionItem(
                    action = SearcherAction.Properties(result),
                    onClick = onAction
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                // Destructive actions
                QuickActionItem(
                    action = SearcherAction.Delete(listOf(result)),
                    onClick = onAction,
                    isDestructive = true
                )
            }
        }
    }
}

@Composable
private fun QuickActionItem(
    action: SearcherAction,
    onClick: (SearcherAction) -> Unit,
    isPrimary: Boolean = false,
    isDestructive: Boolean = action.isDestructive,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick(action) }
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = action.label.get(context),
            modifier = Modifier.size(24.dp),
            tint = when {
                isDestructive -> MaterialTheme.colorScheme.error
                isPrimary -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )

        Spacer(modifier = Modifier.width(16.dp))

        Text(
            text = action.label.get(context),
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                isDestructive -> MaterialTheme.colorScheme.error
                isPrimary -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

private fun isTextFile(result: SearchItem): Boolean {
    // Simple heuristic - in a real implementation, this could be more sophisticated
    val extension = result.path.name.substringAfterLast('.', "").lowercase()
    return extension in setOf(
        "txt", "md", "json", "xml", "html", "css", "js", "kt", "java",
        "py", "c", "cpp", "h", "hpp", "cs", "php", "rb", "go", "rs",
        "yml", "yaml", "toml", "ini", "cfg", "conf", "log"
    )
}

@Preview2
@Composable
private fun SearchResultQuickActionsPreview() {
    val mockPath = LocalPath.build("/storage/emulated/0/Documents/example.txt")
    val mockLookup = LocalPathLookup(
        lookedUp = mockPath,
        fileType = FileType.FILE,
        size = 1024L,
        modifiedAt = kotlin.time.Clock.System.now(),
        target = null
    )

    PreviewWrapper {
        SearchResultQuickActions(
            result = SearchItem.fromLookup(
                lookup = mockLookup,
                matchedQuery = "example"
            ),
            onAction = {},
            onLongPress = {},
            onDismiss = {}
        )
    }
}