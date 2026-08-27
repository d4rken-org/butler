package eu.darken.butler.workspace.ui.clipboard.details

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.InsertDriveFile
import androidx.compose.material.icons.automirrored.twotone.NavigateNext
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.ContentCut
import androidx.compose.material.icons.twotone.ContentPaste
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.ExpandLess
import androidx.compose.material.icons.twotone.ExpandMore
import androidx.compose.material.icons.twotone.FolderOpen
import androidx.compose.material.icons.twotone.TextSnippet
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.workspace.ui.clipboard.mockFileLookup
import eu.darken.butler.common.formatRelativeTime
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.ui.LocalWorkspaceTitles
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet
import eu.darken.butler.workspace.ui.dialogs.InfoField
import eu.darken.butler.workspace.ui.dialogs.InfoValueStyle
import eu.darken.butler.workspace.ui.originWorkspaceLabel
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

@Composable
fun ClipboardInfoBottomSheet(
    clip: ClipboardClip,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
    onNavigateToSource: (() -> Unit)? = null,
    onPaste: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    onCopyPath: ((String) -> Unit)? = null,
) {
    PaneScopedBottomSheet(
        visible = true,
        onDismiss = onDismiss,
        topInset = topInset,
        bottomInset = bottomInset,
        modifier = modifier,
    ) {
        ClipboardInfoContent(
            clip = clip,
            onNavigateToSource = onNavigateToSource,
            onPaste = onPaste,
            onRemove = onRemove,
            onCopyPath = onCopyPath,
        )
    }
}

@Composable
private fun ClipboardInfoContent(
    clip: ClipboardClip,
    onNavigateToSource: (() -> Unit)? = null,
    onPaste: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    onCopyPath: ((String) -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (clip) {
            is ClipboardClip.Paths -> {
                val context = LocalContext.current
                val sources = clip.sourceLocations(context)

                // Header Section
                ClipboardInfoHeader(clip = clip)

                Spacer(modifier = Modifier.height(1.dp))

                // Overview Section
                ClipboardOverviewSection(clip = clip, sources = sources)

                // Files Section
                if (clip.paths.isNotEmpty()) {
                    ClipboardFilesSection(
                        paths = clip.paths,
                        onCopyPath = onCopyPath,
                    )
                }

                // Actions Section
                val hasActions = onNavigateToSource != null || onPaste != null || onRemove != null
                if (hasActions) {
                    ClipboardActionsSection(
                        onNavigateToSource = onNavigateToSource,
                        onPaste = onPaste,
                        onRemove = onRemove,
                        multipleSources = sources.size > 1,
                    )
                }
            }

            is ClipboardClip.Text -> {
                // Header Section
                ClipboardTextInfoHeader(clip = clip)

                Spacer(modifier = Modifier.height(1.dp))

                // Overview Section
                ClipboardTextOverviewSection(clip = clip)

                // Content Section
                ClipboardTextContentSection(clip = clip)

                // Actions Section
                val hasActions = onPaste != null || onRemove != null
                if (hasActions) {
                    ClipboardActionsSection(
                        onNavigateToSource = null,
                        onPaste = onPaste,
                        onRemove = onRemove,
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipboardInfoHeader(
    clip: ClipboardClip.Paths,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when (clip.mode) {
                ClipboardClip.Paths.Mode.COPY -> Icons.TwoTone.ContentCopy
                ClipboardClip.Paths.Mode.CUT -> Icons.TwoTone.ContentCut
            },
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = when (clip.mode) {
                ClipboardClip.Paths.Mode.COPY -> MaterialTheme.colorScheme.primary
                ClipboardClip.Paths.Mode.CUT -> MaterialTheme.colorScheme.tertiary
            },
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = clip.title.asComposable(),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                modifier = Modifier.padding(top = 2.dp),
                text = clip.description.asComposable(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
    }
}

@Composable
private fun ClipboardOverviewSection(
    clip: ClipboardClip.Paths,
    sources: List<String>,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                modifier = Modifier.padding(horizontal = 12.dp),
                text = stringResource(R.string.clipboard_info_overview).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 12.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    InfoField(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.clipboard_info_operation),
                        value = when (clip.mode) {
                            ClipboardClip.Paths.Mode.COPY -> stringResource(R.string.clipboard_copy)
                            ClipboardClip.Paths.Mode.CUT -> stringResource(R.string.clipboard_cut)
                        },
                    )

                    InfoField(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.clipboard_info_items),
                        value = clip.paths.size.toString(),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val workspaceLabel = originWorkspaceLabel(clip.origin)
                    if (workspaceLabel != null) {
                        InfoField(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.clipboard_info_workspace),
                            value = workspaceLabel,
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    InfoField(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.clipboard_info_time),
                        value = formatRelativeTime(clip.clippedAt),
                    )
                }

                if (clip.paths.isNotEmpty()) {
                    InfoField(
                        label = stringResource(
                            if (sources.size == 1) {
                                R.string.clipboard_info_source
                            } else {
                                R.string.clipboard_info_sources
                            },
                        ),
                        value = sources.joinToString("\n"),
                        valueStyle = InfoValueStyle.MONOSPACE,
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipboardFilesSection(
    paths: List<APathLookup<*>>,
    onCopyPath: ((String) -> Unit)? = null,
) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Clickable section title with expand/collapse
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.clipboard_info_items_with_count,
                        paths.size
                    ).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Icon(
                    imageVector = if (isExpanded) Icons.TwoTone.ExpandLess else Icons.TwoTone.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Only show divider and content when expanded
            AnimatedVisibility(visible = isExpanded) {
                Column {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // File list
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp),
                    ) {
                        items(paths) { path ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (onCopyPath != null) {
                                            Modifier.clickable { onCopyPath(path.path) }
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // File/folder icon
                                Icon(
                                    imageVector = getPathIcon(path),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                // File name
                                Text(
                                    text = path.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )

                                // File path (truncated)
                                Text(
                                    text = path.path,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.MiddleEllipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipboardTextInfoHeader(
    clip: ClipboardClip.Text,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.TwoTone.TextSnippet,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = clip.title.asComposable(),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                modifier = Modifier.padding(top = 2.dp),
                text = clip.description.asComposable(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
    }
}

@Composable
private fun ClipboardTextOverviewSection(
    clip: ClipboardClip.Text,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                modifier = Modifier.padding(horizontal = 12.dp),
                text = stringResource(R.string.clipboard_info_overview).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 12.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    InfoField(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.clipboard_text_info_characters),
                        value = clip.content.length.toString(),
                    )

                    InfoField(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.clipboard_text_info_lines),
                        value = (clip.content.count { it == '\n' } + 1).toString(),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val workspaceLabel = originWorkspaceLabel(clip.origin)
                    if (workspaceLabel != null) {
                        InfoField(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.clipboard_info_workspace),
                            value = workspaceLabel,
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    InfoField(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.clipboard_info_time),
                        value = formatRelativeTime(clip.clippedAt),
                    )
                }

                clip.sourcePath?.let { sourcePath ->
                    InfoField(
                        label = stringResource(R.string.clipboard_info_source),
                        value = sourcePath.userReadablePath.get(LocalContext.current),
                        valueStyle = InfoValueStyle.MONOSPACE,
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipboardTextContentSection(
    clip: ClipboardClip.Text,
) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Clickable section title with expand/collapse
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.clipboard_text_info_content).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Icon(
                    imageVector = if (isExpanded) Icons.TwoTone.ExpandLess else Icons.TwoTone.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Show preview when collapsed, full content when expanded
            AnimatedVisibility(visible = !isExpanded) {
                Text(
                    text = clip.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = clip.content,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipboardActionsSection(
    onNavigateToSource: (() -> Unit)? = null,
    onPaste: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    multipleSources: Boolean = false,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.clipboard_info_actions).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (onNavigateToSource != null) {
                    OutlinedButton(
                        onClick = onNavigateToSource,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.TwoTone.NavigateNext,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(
                                if (multipleSources) {
                                    R.string.clipboard_info_go_to_first_source
                                } else {
                                    R.string.clipboard_info_go_to_source
                                },
                            )
                        )
                    }
                }

                if (onPaste != null) {
                    OutlinedButton(
                        onClick = onPaste,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.TwoTone.ContentPaste,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.clipboard_paste))
                    }
                }
            }

            if (onRemove != null) {
                OutlinedButton(
                    onClick = onRemove,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.clipboard_info_remove))
                }
            }
        }
    }
}

private fun getPathIcon(path: APathLookup<*>): ImageVector = when (path.fileType) {
    FileType.DIRECTORY -> Icons.TwoTone.FolderOpen
    else -> Icons.AutoMirrored.TwoTone.InsertDriveFile
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ClipboardInfoBottomSheetPreview() {
    val origin = Workspace.Id(Uuid.random())
    val mockClip = ClipboardClip.Paths(
        origin = origin,
        mode = ClipboardClip.Paths.Mode.COPY,
        paths = listOf(
            mockFileLookup("/storage/emulated/0/Pictures/photo1.jpg"),
            mockFileLookup("/storage/emulated/0/Pictures/photo2.jpg"),
            mockFileLookup("/storage/emulated/0/Documents/report.pdf"),
        ),
        clippedAt = Clock.System.now() - 5.minutes,
    )

    PreviewWrapper {
        CompositionLocalProvider(LocalWorkspaceTitles provides mapOf(origin to "Pictures")) {
            ClipboardInfoBottomSheet(
                clip = mockClip,
                onDismiss = {},
                onNavigateToSource = {},
                onPaste = {},
                onRemove = {},
                onCopyPath = {},
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ClipboardInfoSingleFilePreview() {
    val origin = Workspace.Id(Uuid.random())
    val mockClip = ClipboardClip.Paths(
        origin = origin,
        mode = ClipboardClip.Paths.Mode.CUT,
        paths = listOf(
            mockFileLookup("/storage/emulated/0/Documents/important_document.pdf"),
        ),
        clippedAt = Clock.System.now() - 2.minutes,
    )

    PreviewWrapper {
        CompositionLocalProvider(LocalWorkspaceTitles provides mapOf(origin to "Documents")) {
            ClipboardInfoBottomSheet(
                clip = mockClip,
                onDismiss = {},
                onNavigateToSource = {},
                onPaste = {},
                onRemove = {},
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ClipboardInfoLongPathPreview() {
    val origin = Workspace.Id(Uuid.random())
    val mockClip = ClipboardClip.Paths(
        origin = origin,
        mode = ClipboardClip.Paths.Mode.CUT,
        paths = listOf(
            mockFileLookup(
                "/storage/emulated/0/Android/data/eu.darken.butler/files/backups/2026/quarterly-financial-review-final.pdf"
            ),
        ),
        clippedAt = Clock.System.now() - 12.minutes,
    )

    PreviewWrapper {
        CompositionLocalProvider(LocalWorkspaceTitles provides mapOf(origin to "Backups")) {
            ClipboardInfoBottomSheet(
                clip = mockClip,
                onDismiss = {},
                onNavigateToSource = {},
                onPaste = {},
                onRemove = {},
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ClipboardInfoMultiSourcePreview() {
    val origin = Workspace.Id(Uuid.random())
    val mockClip = ClipboardClip.Paths(
        origin = origin,
        mode = ClipboardClip.Paths.Mode.COPY,
        paths = listOf(
            mockFileLookup("/storage/emulated/0/Pictures/photo1.jpg"),
            mockFileLookup("/storage/emulated/0/Documents/report.pdf"),
            mockFileLookup("/storage/emulated/0/Download/installer.apk"),
        ),
        clippedAt = Clock.System.now() - 7.minutes,
    )

    PreviewWrapper {
        CompositionLocalProvider(LocalWorkspaceTitles provides mapOf(origin to "Home")) {
            ClipboardInfoBottomSheet(
                clip = mockClip,
                onDismiss = {},
                onNavigateToSource = {},
                onPaste = {},
                onRemove = {},
                onCopyPath = {},
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ClipboardInfoTextPreview() {
    val origin = Workspace.Id(Uuid.random())
    val mockClip = ClipboardClip.Text(
        origin = origin,
        content = "Hello, this is a sample text snippet that was copied from the editor.\nIt contains multiple lines.\nAnd more text here.",
        sourcePath = LocalPath.build("/storage/emulated/0/Documents/notes.txt"),
        clippedAt = Clock.System.now() - 3.minutes,
    )

    PreviewWrapper {
        CompositionLocalProvider(LocalWorkspaceTitles provides mapOf(origin to "Notes")) {
            ClipboardInfoBottomSheet(
                clip = mockClip,
                onDismiss = {},
                onPaste = {},
                onRemove = {},
            )
        }
    }
}
