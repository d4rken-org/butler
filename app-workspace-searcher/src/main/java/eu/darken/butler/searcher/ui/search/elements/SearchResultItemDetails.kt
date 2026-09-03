package eu.darken.butler.searcher.ui.search.elements

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.TintedAsyncImage
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.DateTimeStyle
import eu.darken.butler.common.formatDateTime
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.searcher.R
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.ui.search.util.SearcherActionBarItem
import eu.darken.butler.searcher.ui.search.util.getEllipsizedMatchLine
import eu.darken.butler.workspace.ui.actions.FileActionCapabilities
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet
import kotlin.time.Clock

@Composable
fun SearchResultItemDetails(
    result: SearchItem,
    trashEnabled: Boolean,
    onAction: (SearcherActionBarItem) -> Unit,
    onLongPress: (SearchItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
) {
    val caps = remember(result.lookup) { FileActionCapabilities.of(result.lookup) }

    PaneScopedBottomSheet(
        visible = true,
        onDismiss = onDismiss,
        topInset = topInset,
        bottomInset = bottomInset,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            // Header with file info
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
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
                        result.lookup.modifiedAt?.let { append(formatDateTime(it, DateTimeStyle.FULL)) } ?: append("?")
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

            // Match context - shown if this file was found via content search
            result.matchContext?.takeIf { it.lineNumber != null }?.let { match ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = stringResource(R.string.searcher_match_context_label),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    val previewFontStyle =
                        MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    // Context lines before match
                    match.contextBefore?.forEachIndexed { index, line ->
                        val lineNumber = (match.lineNumber ?: 0) - match.contextBefore.size + index
                        Text(
                            text = stringResource(
                                R.string.searcher_match_line_label,
                                lineNumber,
                                line
                            ),
                            style = previewFontStyle,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(vertical = 1.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // The matched line (highlighted)
                    val ellipsizedLine = getEllipsizedMatchLine(
                        line = match.matchedLine ?: "",
                        startIndex = match.startIndex ?: 0,
                        endIndex = match.endIndex ?: 0,
                        maxLength = 100
                    )
                    Text(
                        text = stringResource(
                            R.string.searcher_match_line_label,
                            match.lineNumber ?: 0,
                            ellipsizedLine
                        ),
                        style = previewFontStyle,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )

                    // Context lines after match
                    match.contextAfter?.forEachIndexed { index, line ->
                        val lineNumber = (match.lineNumber ?: 0) + index + 1
                        Text(
                            text = stringResource(
                                R.string.searcher_match_line_label,
                                lineNumber,
                                line
                            ),
                            style = previewFontStyle,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(vertical = 1.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            Column(
                modifier = Modifier.padding(top = 4.dp)
            ) {
                // Above "Open": an app bundle is also an archive, but installing it is what the user
                // came for.
                if (caps.isInstallable) {
                    QuickActionItem(
                        action = SearcherActionBarItem.Install(result),
                        onClick = onAction,
                        isPrimary = true
                    )
                }

                // Primary actions
                if (result.fileType != FileType.DIRECTORY) {
                    QuickActionItem(
                        action = SearcherActionBarItem.Open(result),
                        onClick = onAction,
                        isPrimary = true
                    )

                    QuickActionItem(
                        action = SearcherActionBarItem.OpenInTab(result),
                        onClick = onAction,
                        isPrimary = true
                    )
                }

                if (caps.isText) {
                    QuickActionItem(
                        action = SearcherActionBarItem.OpenInEditor(result),
                        onClick = onAction,
                        isPrimary = true
                    )
                }

                if (result.fileType != FileType.DIRECTORY && caps.canHandOffToOtherApps) {
                    QuickActionItem(
                        action = SearcherActionBarItem.OpenWith(result),
                        onClick = onAction,
                        isPrimary = true
                    )
                }

                QuickActionItem(
                    action = SearcherActionBarItem.OpenInExplorer(result),
                    onClick = onAction,
                    isPrimary = true
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                // Clipboard actions
                QuickActionItem(
                    action = SearcherActionBarItem.Copy(listOf(result)),
                    onClick = onAction
                )

                QuickActionItem(
                    action = SearcherActionBarItem.Cut(listOf(result)),
                    onClick = onAction
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                // Additional actions
                if (caps.canHandOffToOtherApps) {
                    QuickActionItem(
                        action = SearcherActionBarItem.Share(listOf(result)),
                        onClick = onAction
                    )
                }

                QuickActionItem(
                    action = SearcherActionBarItem.CopyPath(result),
                    onClick = onAction
                )

                QuickActionItem(
                    action = SearcherActionBarItem.ShowProperties(result),
                    onClick = onAction
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                // Destructive actions
                QuickActionItem(
                    action = SearcherActionBarItem.Delete(listOf(result), trashEnabled),
                    onClick = onAction,
                )
            }
        }
    }
}

@Composable
private fun QuickActionItem(
    action: SearcherActionBarItem,
    onClick: (SearcherActionBarItem) -> Unit,
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

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SearchResultItemDetailsPreview() {
    val mockPath = LocalPath.build("/storage/emulated/0/Documents/example.txt")
    val mockLookup = LocalPathLookup(
        lookedUp = mockPath,
        fileType = FileType.FILE,
        size = 1024L,
        modifiedAt = Clock.System.now(),
        target = null
    )

    PreviewWrapper {
        SearchResultItemDetails(
            result = SearchItem.fromLookup(
                lookup = mockLookup,
                matchedQuery = "example",
            ),
            trashEnabled = true,
            onAction = {},
            onLongPress = {},
            onDismiss = {},
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SearchResultItemDetailsWithContextPreview() {
    val mockPath = LocalPath.build("/storage/emulated/0/Documents/example.txt")
    val mockLookup = LocalPathLookup(
        lookedUp = mockPath,
        fileType = FileType.FILE,
        size = 1024L,
        modifiedAt = Clock.System.now(),
        target = null
    )

    val mockMatchContext = SearchItem.MatchContext(
        lineNumber = 42,
        matchedLine = "This is a very long line at the beginning with lots of text before the example match occurs in the middle and then continues with even more text after it to demonstrate the ellipsis functionality",
        startIndex = 72,
        endIndex = 79,
        contextBefore = listOf(
            "// Previous context line 1",
            "// Previous context line 2"
        ),
        contextAfter = listOf(
            "// Following context line 1",
            "// Following context line 2"
        )
    )

    PreviewWrapper {
        SearchResultItemDetails(
            result = SearchItem.fromLookup(
                lookup = mockLookup,
                matchedQuery = "example",
                matchContext = mockMatchContext
            ),
            trashEnabled = false,
            onAction = {},
            onLongPress = {},
            onDismiss = {},
        )
    }
}