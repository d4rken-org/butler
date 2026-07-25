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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
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
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheet
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
    val isInPreview = LocalInspectionMode.current

    if (isInPreview) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            ClipboardInfoContent(
                clip = clip,
                onNavigateToSource = onNavigateToSource,
                onPaste = onPaste,
                onRemove = onRemove,
                onCopyPath = onCopyPath,
            )
        }
    } else {
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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (clip) {
            is ClipboardClip.Paths -> {
                // Header Section
                ClipboardInfoHeader(clip = clip)

                Spacer(modifier = Modifier.height(1.dp))

                // Overview Section
                ClipboardOverviewSection(clip = clip)

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
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ClipboardOverviewSection(
    clip: ClipboardClip.Paths,
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
                text = stringResource(R.string.clipboard_info_overview).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Operation type
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.clipboard_info_operation),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = when (clip.mode) {
                            ClipboardClip.Paths.Mode.COPY -> stringResource(R.string.clipboard_copy)
                            ClipboardClip.Paths.Mode.CUT -> stringResource(R.string.clipboard_cut)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                // Item count
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.clipboard_info_items),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = clip.paths.size.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                // Source location
                if (clip.paths.isNotEmpty()) {
                    val sourcePath = clip.paths.first().parent
                        ?.userReadablePath?.get(LocalContext.current)
                        ?: "/"

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.clipboard_info_source),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = sourcePath,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Origin workspace
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.clipboard_info_workspace),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = clip.origin.shortTag,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                // Time timestamp
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.clipboard_info_time),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatRelativeTime(clip.clippedAt),
                        style = MaterialTheme.typography.bodyMedium,
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
                overflow = TextOverflow.Ellipsis,
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
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.clipboard_info_overview).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Character count
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.clipboard_text_info_characters),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = clip.content.length.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                // Line count
                val lineCount = clip.content.count { it == '\n' } + 1
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.clipboard_text_info_lines),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = lineCount.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                // Source file (if any)
                clip.sourcePath?.let { sourcePath ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.clipboard_info_source),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = sourcePath.userReadablePath.get(LocalContext.current),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Origin workspace
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.clipboard_info_workspace),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = clip.origin.shortTag,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                // Time timestamp
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.clipboard_info_time),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatRelativeTime(clip.clippedAt),
                        style = MaterialTheme.typography.bodyMedium,
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
                        Text(stringResource(R.string.clipboard_info_go_to_source))
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
    val mockClip = ClipboardClip.Paths(
        origin = Workspace.Id(Uuid.random()),
        mode = ClipboardClip.Paths.Mode.COPY,
        paths = listOf(
            mockFileLookup("/storage/emulated/0/Pictures/photo1.jpg"),
            mockFileLookup("/storage/emulated/0/Pictures/photo2.jpg"),
            mockFileLookup("/storage/emulated/0/Documents/report.pdf"),
        ),
        clippedAt = Clock.System.now() - 5.minutes,
    )

    PreviewWrapper {
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

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ClipboardInfoSingleFilePreview() {
    val mockClip = ClipboardClip.Paths(
        origin = Workspace.Id(Uuid.random()),
        mode = ClipboardClip.Paths.Mode.CUT,
        paths = listOf(
            mockFileLookup("/storage/emulated/0/Documents/important_document.pdf"),
        ),
        clippedAt = Clock.System.now() - 2.minutes,
    )

    PreviewWrapper {
        ClipboardInfoBottomSheet(
            clip = mockClip,
            onDismiss = {},
            onNavigateToSource = {},
            onPaste = {},
            onRemove = {},
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ClipboardInfoTextPreview() {
    val mockClip = ClipboardClip.Text(
        origin = Workspace.Id(Uuid.random()),
        content = "Hello, this is a sample text snippet that was copied from the editor.\nIt contains multiple lines.\nAnd more text here.",
        sourcePath = LocalPath.build("/storage/emulated/0/Documents/notes.txt"),
        clippedAt = Clock.System.now() - 3.minutes,
    )

    PreviewWrapper {
        ClipboardInfoBottomSheet(
            clip = mockClip,
            onDismiss = {},
            onPaste = {},
            onRemove = {},
        )
    }
}