package eu.darken.butler.explorer.ui.explorer

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ClearAll
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.ContentCut
import androidx.compose.material.icons.twotone.ContentPaste
import androidx.compose.material.icons.twotone.ExpandLess
import androidx.compose.material.icons.twotone.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.RawPath
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

@Composable
fun ExplorerClipboardBar(
    modifier: Modifier = Modifier,
    initialExpanded: Boolean = false,
    clipboardEntries: List<ClipboardClip>,
    onPasteClick: (ClipboardClip) -> Unit,
    onRemoveClick: (ClipboardClip) -> Unit,
    onEntryClick: (ClipboardClip) -> Unit = {},
    onClearAll: () -> Unit = {},
) {
    if (clipboardEntries.isEmpty()) return

    var isExpanded by remember { mutableStateOf(initialExpanded) }
    val maxEntries = 4
    val latestEntry = clipboardEntries.first()
    val additionalEntries = if (isExpanded && clipboardEntries.size > 1) {
        clipboardEntries.drop(1).take(maxEntries - 1)
    } else emptyList()

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier.animateContentSize(
                animationSpec = tween(durationMillis = 300)
            )
        ) {
            // Expand/Collapse header row (when multiple entries)
            if (clipboardEntries.size > 1) {
                ClipboardHeaderRow(
                    isExpanded = isExpanded,
                    entryCount = clipboardEntries.size,
                    onExpandClick = { isExpanded = !isExpanded },
                    onClearAllClick = onClearAll,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            }

            // Additional entries (when expanded) - reversed order for upward expansion
            additionalEntries.reversed().forEach { entry ->
                ClipboardEntry(
                    entry = entry,
                    onPasteClick = { onPasteClick(entry) },
                    onEntryClick = { onEntryClick(entry) },
                    showOrigin = true, // Show origin for expanded entries
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 32.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            }

            // Latest entry (always at bottom)
            ClipboardEntry(
                entry = latestEntry,
                onPasteClick = { onPasteClick(latestEntry) },
                onEntryClick = { onEntryClick(latestEntry) },
                showOrigin = isExpanded,
            )
        }
    }
}

@Composable
private fun ClipboardHeaderRow(
    modifier: Modifier = Modifier,
    isExpanded: Boolean,
    entryCount: Int,
    onExpandClick: () -> Unit,
    onClearAllClick: () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Collapse/Expand button with forced height
        TextButton(
            onClick = onExpandClick,
            modifier = Modifier.height(40.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.TwoTone.ExpandLess else Icons.TwoTone.ExpandMore,
                contentDescription = if (isExpanded) "Hide clipboard" else "Show more",
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (isExpanded) "Hide clipboard" else "Show more ($entryCount items)",
                style = MaterialTheme.typography.labelSmall,
            )
        }

        // Clear All button (only when expanded)
        if (isExpanded) {
            TextButton(
                onClick = onClearAllClick,
                modifier = Modifier.height(40.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Icon(
                    imageVector = Icons.TwoTone.ClearAll,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Clear All",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun ClipboardEntry(
    modifier: Modifier = Modifier,
    entry: ClipboardClip,
    onPasteClick: () -> Unit,
    onEntryClick: () -> Unit,
    showOrigin: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onEntryClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (entry) {
            is ClipboardClip.Paths -> {
                // Copy/Cut Icon at very left
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

                // Vertical column for all text content
                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    // First text row: Title + Timestamp
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Title text (takes most space)
                        Text(
                            text = formatClipboardTitle(entry),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )

                        // Timestamp
                        Text(
                            text = formatTimestamp(entry.clippedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp, end = 8.dp),
                        )
                    }

                    // Second text row: Subtitle
                    Text(
                        text = formatClipboardSubtitle(entry),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    // Third text row: Origin (only in expanded mode)
                    if (showOrigin) {
                        Text(
                            text = "Origin: Workspace ${entry.origin.shortTag}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Paste button (vertically centered with entire content)
                IconButton(
                    onClick = onPasteClick,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.ContentPaste,
                        contentDescription = "Paste",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}


private fun formatClipboardTitle(entry: ClipboardClip.Paths): String {
    val action = when (entry.mode) {
        ClipboardClip.Paths.Mode.COPY -> "Copy"
        ClipboardClip.Paths.Mode.CUT -> "Cut"
    }

    val count = entry.paths.size
    return when {
        count == 1 -> "$action ${entry.paths.first().name}"
        count > 1 -> "$action $count items"
        else -> action
    }
}

private fun formatClipboardSubtitle(entry: ClipboardClip.Paths): String {
    if (entry.paths.isEmpty()) return ""

    val firstPath = entry.paths.first()
    val pathSegments = firstPath.path.split("/").filter { it.isNotEmpty() }
    val parentName = if (pathSegments.size > 1) {
        pathSegments[pathSegments.size - 2]
    } else {
        "/"
    }

    return "from $parentName"
}

private fun formatTimestamp(instant: kotlin.time.Instant): String {
    val duration = Clock.System.now() - instant

    return when {
        duration.inWholeMinutes < 1 -> "just now"
        duration.inWholeMinutes < 60 -> "${duration.inWholeMinutes}m ago"
        duration.inWholeHours < 24 -> "${duration.inWholeHours}h ago"
        else -> "${duration.inWholeDays}d ago"
    }
}

@Preview2
@Composable
fun ExplorerClipboardBarPreview() {
    val mockEntries = listOf(
        ClipboardClip.Paths(
            origin = Workspace.Id(UUID.randomUUID()),
            mode = ClipboardClip.Paths.Mode.COPY,
            paths = listOf(
                RawPath.build("/storage/emulated/0/Pictures/photo1.jpg"),
                RawPath.build("/storage/emulated/0/Pictures/photo2.jpg"),
                RawPath.build("/storage/emulated/0/Pictures/photo3.jpg"),
            ),
            clippedAt = Clock.System.now() - 5.minutes,
        ),
        ClipboardClip.Paths(
            origin = Workspace.Id(UUID.randomUUID()),
            mode = ClipboardClip.Paths.Mode.CUT,
            paths = listOf(
                RawPath.build("/storage/emulated/0/Documents/report.pdf"),
            ),
            clippedAt = Clock.System.now() - 2.minutes,
        ),
    )

    PreviewWrapper {
        ExplorerClipboardBar(
            clipboardEntries = mockEntries,
            onPasteClick = {},
            onRemoveClick = {},
        )
    }
}

@Preview2
@Composable
fun ExplorerClipboardBarExpandedPreview() {
    val mockEntries = listOf(
        ClipboardClip.Paths(
            origin = Workspace.Id(UUID.randomUUID()),
            mode = ClipboardClip.Paths.Mode.COPY,
            paths = listOf(
                RawPath.build("/storage/emulated/0/Pictures/photo1.jpg"),
                RawPath.build("/storage/emulated/0/Pictures/photo2.jpg"),
                RawPath.build("/storage/emulated/0/Pictures/photo3.jpg"),
            ),
            clippedAt = Clock.System.now() - 5.minutes,
        ),
        ClipboardClip.Paths(
            origin = Workspace.Id(UUID.randomUUID()),
            mode = ClipboardClip.Paths.Mode.CUT,
            paths = listOf(
                RawPath.build("/storage/emulated/0/Documents/report.pdf"),
            ),
            clippedAt = Clock.System.now() - 2.minutes,
        ),
        ClipboardClip.Paths(
            origin = Workspace.Id(UUID.randomUUID()),
            mode = ClipboardClip.Paths.Mode.COPY,
            paths = listOf(
                RawPath.build("/storage/emulated/0/Downloads/app.apk"),
            ),
            clippedAt = Clock.System.now() - 1.minutes,
        ),
    )

    PreviewWrapper {
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            ExplorerClipboardBar(
                initialExpanded = true,
                clipboardEntries = mockEntries,
                onPasteClick = {},
                onRemoveClick = {},
                onClearAll = {},
            )
        }
    }
}