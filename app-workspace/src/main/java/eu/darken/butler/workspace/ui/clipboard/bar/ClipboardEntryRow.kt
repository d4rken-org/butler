package eu.darken.butler.workspace.ui.clipboard.bar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.ContentCut
import androidx.compose.material.icons.twotone.ContentPaste
import androidx.compose.material.icons.twotone.FolderOpen
import androidx.compose.material.icons.twotone.Workspaces
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.formatRelativeTime
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

@Composable
fun ClipboardEntryRow(
    entry: ClipboardClip,
    onPasteClick: () -> Unit,
    onEntryClick: () -> Unit,
    modifier: Modifier = Modifier,
    showOrigin: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable { onEntryClick() }
            .padding(vertical = if (showOrigin) 8.dp else 4.dp)
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (entry) {
            is ClipboardClip.Paths -> {
                if (showOrigin) {
                    // EXPANDED MODE: Detailed design with icons for each row
                    Column(
                        modifier = Modifier.weight(1f),
                    ) {
                        // First text row: Copy/Cut icon + Title + Timestamp
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Copy/Cut Icon (smaller)
                            Icon(
                                imageVector = when (entry.mode) {
                                    ClipboardClip.Paths.Mode.COPY -> Icons.TwoTone.ContentCopy
                                    ClipboardClip.Paths.Mode.CUT -> Icons.TwoTone.ContentCut
                                },
                                contentDescription = entry.mode.name,
                                modifier = Modifier.size(12.dp),
                                tint = when (entry.mode) {
                                    ClipboardClip.Paths.Mode.COPY -> MaterialTheme.colorScheme.primary
                                    ClipboardClip.Paths.Mode.CUT -> MaterialTheme.colorScheme.tertiary
                                }
                            )

                            Spacer(modifier = Modifier.width(6.dp))

                            // Title text (takes most space)
                            Text(
                                text = entry.title.asComposable(),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )

                            // Timestamp
                            Text(
                                text = formatRelativeTime(entry.clippedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp, end = 8.dp),
                            )
                        }

                        // Second text row: Folder icon + Subtitle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Source/From Icon
                            Icon(
                                imageVector = Icons.TwoTone.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            Spacer(modifier = Modifier.width(6.dp))

                            Text(
                                text = entry.description.asComposable(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        // Third text row: Workspace icon + Origin
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Workspace Icon
                            Icon(
                                imageVector = Icons.TwoTone.Workspaces,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )

                            Spacer(modifier = Modifier.width(6.dp))

                            Text(
                                text = stringResource(R.string.clipboard_origin, entry.origin.shortTag),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                } else {
                    // COLLAPSED MODE: Simple design without individual icons
                    Icon(
                        imageVector = when (entry.mode) {
                            ClipboardClip.Paths.Mode.COPY -> Icons.TwoTone.ContentCopy
                            ClipboardClip.Paths.Mode.CUT -> Icons.TwoTone.ContentCut
                        },
                        contentDescription = entry.mode.name,
                        modifier = Modifier.size(20.dp),
                        tint = when (entry.mode) {
                            ClipboardClip.Paths.Mode.COPY -> MaterialTheme.colorScheme.primary
                            ClipboardClip.Paths.Mode.CUT -> MaterialTheme.colorScheme.tertiary
                        }
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(
                        modifier = Modifier.weight(1f),
                    ) {
                        // Title + Timestamp row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = entry.title.asComposable(),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )

                            Text(
                                text = formatRelativeTime(entry.clippedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp, end = 8.dp),
                            )
                        }

                        // Simple subtitle
                        Text(
                            text = entry.description.asComposable(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Paste button
                IconButton(
                    onClick = onPasteClick
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.ContentPaste,
                        contentDescription = stringResource(R.string.clipboard_paste),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Preview2
@Composable
private fun ClipboardEntryRowCollapsedPreview() {
    PreviewWrapper {
        ClipboardEntryRow(
            entry = ClipboardClip.Paths(
                origin = Workspace.Id(Uuid.random()),
                mode = ClipboardClip.Paths.Mode.COPY,
                paths = listOf(
                    LocalPath.build("/storage/emulated/0/Pictures/photo1.jpg"),
                    LocalPath.build("/storage/emulated/0/Pictures/photo2.jpg"),
                    LocalPath.build("/storage/emulated/0/Pictures/photo3.jpg"),
                ),
                clippedAt = Clock.System.now() - 5.minutes,
            ),
            onPasteClick = {},
            onEntryClick = {},
            showOrigin = false,
        )
    }
}

@Preview2
@Composable
private fun ClipboardEntryRowExpandedPreview() {
    PreviewWrapper {
        ClipboardEntryRow(
            entry = ClipboardClip.Paths(
                origin = Workspace.Id(Uuid.random()),
                mode = ClipboardClip.Paths.Mode.CUT,
                paths = listOf(
                    LocalPath.build("/storage/emulated/0/Documents/report.pdf"),
                ),
                clippedAt = Clock.System.now() - 2.minutes,
            ),
            onPasteClick = {},
            onEntryClick = {},
            showOrigin = true,
        )
    }
}