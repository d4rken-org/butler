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
import androidx.compose.material.icons.twotone.NoteAdd
import androidx.compose.material.icons.twotone.TextSnippet
import androidx.compose.material.icons.twotone.Workspaces
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.workspace.ui.clipboard.mockFileLookup
import eu.darken.butler.common.formatRelativeTime
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.ui.LocalWorkspaceTitles
import eu.darken.butler.workspace.ui.originWorkspaceLabel
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

@Composable
fun ClipboardEntryRow(
    entry: ClipboardClip,
    workspaceType: Workspace.Type,
    onPasteClick: () -> Unit,
    onEntryClick: () -> Unit,
    modifier: Modifier = Modifier,
    showOrigin: Boolean = false,
) {
    val (pasteIcon, pasteLabel) = when {
        workspaceType == Workspace.Type.SEARCHER -> Icons.TwoTone.FolderOpen to R.string.clipboard_open_in_explorer
        workspaceType == Workspace.Type.EXPLORER && entry is ClipboardClip.Text -> Icons.TwoTone.NoteAdd to R.string.clipboard_text_paste_as_file
        else -> Icons.TwoTone.ContentPaste to R.string.clipboard_paste
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .clickable { onEntryClick() }
            .padding(vertical = if (showOrigin) 8.dp else 0.dp)
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = when (entry.mode) {
                                    ClipboardClip.Paths.Mode.COPY -> Icons.TwoTone.ContentCopy
                                    ClipboardClip.Paths.Mode.CUT -> Icons.TwoTone.ContentCut
                                },
                                contentDescription = entry.mode.name,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                            )

                            Spacer(modifier = Modifier.width(6.dp))

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
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.TwoTone.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            )

                            Spacer(modifier = Modifier.width(6.dp))

                            Text(
                                text = entry.description.asComposable(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        val originLabel = originWorkspaceLabel(entry.origin)
                        if (originLabel != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.TwoTone.Workspaces,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                                )

                                Spacer(modifier = Modifier.width(6.dp))

                                Text(
                                    text = stringResource(R.string.clipboard_origin, originLabel),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
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
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = entry.title.asComposable(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )

                            Text(
                                text = formatRelativeTime(entry.clippedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }

                        Text(
                            text = entry.description.asComposable(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                IconButton(
                    onClick = onPasteClick
                ) {
                    Icon(
                        imageVector = pasteIcon,
                        contentDescription = stringResource(pasteLabel),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }

            is ClipboardClip.Text -> {
                if (showOrigin) {
                    // EXPANDED MODE: Detailed design with icons for each row
                    Column(
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.TwoTone.TextSnippet,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            )

                            Spacer(modifier = Modifier.width(6.dp))

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
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Spacer(modifier = Modifier.width(18.dp))

                            Text(
                                text = entry.preview,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        val originLabel = originWorkspaceLabel(entry.origin)
                        if (originLabel != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.TwoTone.Workspaces,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                                )

                                Spacer(modifier = Modifier.width(6.dp))

                                Text(
                                    text = stringResource(R.string.clipboard_origin, originLabel),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                } else {
                    // COLLAPSED MODE: Simple design without individual icons
                    Icon(
                        imageVector = Icons.TwoTone.TextSnippet,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = entry.title.asComposable(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )

                            Text(
                                text = formatRelativeTime(entry.clippedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }

                        Text(
                            text = entry.preview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                IconButton(
                    onClick = onPasteClick
                ) {
                    Icon(
                        imageVector = pasteIcon,
                        contentDescription = stringResource(pasteLabel),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ClipboardEntryRowCollapsedPreview() {
    ClipboardEntryRow(
        entry = ClipboardClip.Paths(
            origin = Workspace.Id(Uuid.random()),
            mode = ClipboardClip.Paths.Mode.COPY,
            paths = listOf(
                mockFileLookup("/storage/emulated/0/Pictures/photo1.jpg"),
                mockFileLookup("/storage/emulated/0/Pictures/photo2.jpg"),
                mockFileLookup("/storage/emulated/0/Pictures/photo3.jpg"),
            ),
            clippedAt = Clock.System.now() - 5.minutes,
        ),
        workspaceType = Workspace.Type.EXPLORER,
        onPasteClick = {},
        onEntryClick = {},
        showOrigin = false,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ClipboardEntryRowExpandedPreview() {
    val origin = Workspace.Id(Uuid.random())
    CompositionLocalProvider(LocalWorkspaceTitles provides mapOf(origin to "Documents")) {
        ClipboardEntryRow(
            entry = ClipboardClip.Paths(
                origin = origin,
                mode = ClipboardClip.Paths.Mode.CUT,
                paths = listOf(
                    mockFileLookup("/storage/emulated/0/Documents/report.pdf"),
                ),
                clippedAt = Clock.System.now() - 2.minutes,
            ),
            workspaceType = Workspace.Type.SEARCHER,
            onPasteClick = {},
            onEntryClick = {},
            showOrigin = true,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ClipboardEntryRowTextCollapsedPreview() {
    ClipboardEntryRow(
        entry = ClipboardClip.Text(
            origin = Workspace.Id(Uuid.random()),
            content = "Hello, this is a sample text snippet that was copied from the editor.",
            clippedAt = Clock.System.now() - 3.minutes,
        ),
        workspaceType = Workspace.Type.EXPLORER,
        onPasteClick = {},
        onEntryClick = {},
        showOrigin = false,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ClipboardEntryRowTextExpandedPreview() {
    val origin = Workspace.Id(Uuid.random())
    CompositionLocalProvider(LocalWorkspaceTitles provides mapOf(origin to "Editor")) {
        ClipboardEntryRow(
            entry = ClipboardClip.Text(
                origin = origin,
                content = "function greet(name) {\n  return `Hello, \${name}!`;\n}",
                clippedAt = Clock.System.now() - 1.minutes,
            ),
            workspaceType = Workspace.Type.EXPLORER,
            onPasteClick = {},
            onEntryClick = {},
            showOrigin = true,
        )
    }
}