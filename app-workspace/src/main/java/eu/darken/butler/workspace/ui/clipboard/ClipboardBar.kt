package eu.darken.butler.workspace.ui.clipboard

import android.icu.text.RelativeDateTimeFormatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ClearAll
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.ContentCut
import androidx.compose.material.icons.twotone.ContentPaste
import androidx.compose.material.icons.twotone.ExpandLess
import androidx.compose.material.icons.twotone.ExpandMore
import androidx.compose.material.icons.twotone.FolderOpen
import androidx.compose.material.icons.twotone.Workspaces
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.RawPath
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import java.util.Locale
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import eu.darken.butler.common.R as CommonR

@Composable
fun ClipboardBar(
    modifier: Modifier = Modifier,
    initialExpanded: Boolean = false,
    clipboardEntries: List<ClipboardClip>,
    onPasteClick: (ClipboardClip) -> Unit,
    onRemoveClick: (ClipboardClip) -> Unit,
    onEntryClick: (ClipboardClip) -> Unit = {},
    onClearAll: () -> Unit = {},
) {
    // Preserve expansion state across clipboard changes
    var isExpanded by remember(clipboardEntries.size > 1) {
        mutableStateOf(initialExpanded)
    }

    // State for cascading clear all animation
    var clearAllAnimationTrigger by remember { mutableLongStateOf(0L) }

    // Handle cascading clear all animation
    LaunchedEffect(clearAllAnimationTrigger) {
        if (clearAllAnimationTrigger > 0L) {
            // Wait for all swipe animations to complete before clearing
            val totalAnimationTime = (clipboardEntries.size * 300L) + 800L
            kotlinx.coroutines.delay(totalAnimationTime)
            onClearAll()
        }
    }

    val maxEntries = 4
    val latestEntry = clipboardEntries.firstOrNull()
    val additionalEntries = if (isExpanded && clipboardEntries.size > 1) {
        clipboardEntries.drop(1).take(maxEntries - 1)
    } else emptyList()

    AnimatedVisibility(
        visible = clipboardEntries.isNotEmpty(),
        modifier = modifier,
        enter = slideInVertically { it } + fadeIn(animationSpec = tween(300)),
        exit = slideOutVertically { it } + fadeOut(animationSpec = tween(300))
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(16.dp)
                ),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(
                modifier = Modifier.animateContentSize(
                    animationSpec = tween(durationMillis = 300)
                )
            ) {
                // Expand/Collapse header row (when multiple entries)
                AnimatedVisibility(
                    visible = clipboardEntries.size > 1,
                    enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(animationSpec = tween(300)),
                    exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(animationSpec = tween(300))
                ) {
                    Column {
                        ClipboardHeaderRow(
                            isExpanded = isExpanded,
                            entryCount = clipboardEntries.size,
                            onExpandClick = { isExpanded = !isExpanded },
                            onClearAllClick = { clearAllAnimationTrigger = System.currentTimeMillis() },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 32.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                    }
                }

                // Additional entries (when expanded) - already in correct order (oldest first)
                additionalEntries.forEachIndexed { index, entry ->
                    key(entry.id) {
                        SwipeToDismissEntry(
                            entry = entry,
                            onPasteClick = { onPasteClick(entry) },
                            onEntryClick = { onEntryClick(entry) },
                            onRemoveClick = { onRemoveClick(entry) },
                            showOrigin = true, // Show origin for expanded entries
                            triggerDismiss = clearAllAnimationTrigger,
                            dismissDelay = index * 300L, // Cascade delay
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 32.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }

                // Latest entry (always at bottom)
                latestEntry?.let { entry ->
                    key(entry.id) {
                        SwipeToDismissEntry(
                            entry = entry,
                            onPasteClick = { onPasteClick(entry) },
                            onEntryClick = { onEntryClick(entry) },
                            onRemoveClick = { onRemoveClick(entry) },
                            showOrigin = isExpanded,
                            triggerDismiss = clearAllAnimationTrigger,
                            dismissDelay = additionalEntries.size * 300L, // Latest entry has longest delay
                        )
                    }
                }
            }
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
                    contentDescription = stringResource(R.string.clipboard_hide),
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.clipboard_hide),
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

@Composable
private fun SwipeToDismissEntry(
    modifier: Modifier = Modifier,
    entry: ClipboardClip,
    onPasteClick: () -> Unit,
    onEntryClick: () -> Unit,
    onRemoveClick: () -> Unit,
    showOrigin: Boolean = false,
    triggerDismiss: Long = 0L,
    dismissDelay: Long = 0L,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            when (dismissValue) {
                SwipeToDismissBoxValue.EndToStart -> {
                    onRemoveClick()
                    true
                }
                else -> false
            }
        }
    )

    // Handle programmatic dismiss trigger for clear all animation
    LaunchedEffect(triggerDismiss) {
        if (triggerDismiss > 0L) {
            kotlinx.coroutines.delay(dismissDelay)
            dismissState.dismiss(SwipeToDismissBoxValue.EndToStart)
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            // Red background with close icon and text when swiping
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.Close,
                        contentDescription = stringResource(CommonR.string.general_dismiss_action),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(CommonR.string.general_dismiss_action),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    ) {
        ClipboardEntry(
            entry = entry,
            onPasteClick = onPasteClick,
            onEntryClick = onEntryClick,
            showOrigin = showOrigin,
        )
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
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable { onEntryClick() }
            .padding(horizontal = 16.dp, vertical = if (showOrigin) 8.dp else 4.dp),
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
                                text = formatClipboardSubtitle(entry),
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
                                text = formatClipboardTitle(entry),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )

                            Text(
                                text = formatTimestamp(entry.clippedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp, end = 8.dp),
                            )
                        }

                        // Simple subtitle
                        Text(
                            text = formatClipboardSubtitle(entry),
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


@Composable
private fun formatClipboardTitle(entry: ClipboardClip.Paths): String {
    val action = when (entry.mode) {
        ClipboardClip.Paths.Mode.COPY -> stringResource(R.string.clipboard_copy)
        ClipboardClip.Paths.Mode.CUT -> stringResource(R.string.clipboard_cut)
    }

    val count = entry.paths.size
    return when {
        count == 1 -> "$action ${entry.paths.first().name}"
        count > 1 -> stringResource(R.string.clipboard_items_count, count).let { "$action $it" }
        else -> action
    }
}

@Composable
private fun formatClipboardSubtitle(entry: ClipboardClip.Paths): String {
    if (entry.paths.isEmpty()) return ""

    val firstPath = entry.paths.first()
    val parentName = if (firstPath.segments.size > 1) {
        firstPath.segments[firstPath.segments.size - 2]
    } else {
        "/"
    }

    return stringResource(R.string.clipboard_from, parentName)
}

private fun formatTimestamp(instant: kotlin.time.Instant): String {
    val formatter = RelativeDateTimeFormatter.getInstance(Locale.getDefault())
    val duration = Clock.System.now() - instant

    return when {
        duration.inWholeMinutes < 1 -> formatter.format(
            0.0,
            RelativeDateTimeFormatter.Direction.LAST,
            RelativeDateTimeFormatter.RelativeUnit.MINUTES
        )
        duration.inWholeMinutes < 60 -> formatter.format(
            duration.inWholeMinutes.toDouble(),
            RelativeDateTimeFormatter.Direction.LAST,
            RelativeDateTimeFormatter.RelativeUnit.MINUTES
        )
        duration.inWholeHours < 24 -> formatter.format(
            duration.inWholeHours.toDouble(),
            RelativeDateTimeFormatter.Direction.LAST,
            RelativeDateTimeFormatter.RelativeUnit.HOURS
        )
        else -> formatter.format(
            duration.inWholeDays.toDouble(),
            RelativeDateTimeFormatter.Direction.LAST,
            RelativeDateTimeFormatter.RelativeUnit.DAYS
        )
    }
}

@Preview2
@Composable
fun ClipboardBarPreview() {
    val mockEntries = listOf(
        ClipboardClip.Paths(
            origin = Workspace.Id(UUID.randomUUID()),
            mode = ClipboardClip.Paths.Mode.COPY,
            paths = listOf(
                LocalPath.build("/storage/emulated/0/Pictures/photo1.jpg"),
                LocalPath.build("/storage/emulated/0/Pictures/photo2.jpg"),
                LocalPath.build("/storage/emulated/0/Pictures/photo3.jpg"),
            ),
            clippedAt = Clock.System.now() - 5.minutes,
        ),
        ClipboardClip.Paths(
            origin = Workspace.Id(UUID.randomUUID()),
            mode = ClipboardClip.Paths.Mode.CUT,
            paths = listOf(
                LocalPath.build("/storage/emulated/0/Documents/report.pdf"),
            ),
            clippedAt = Clock.System.now() - 2.minutes,
        ),
    )

    PreviewWrapper {
        ClipboardBar(
            clipboardEntries = mockEntries,
            onPasteClick = {},
            onRemoveClick = {},
        )
    }
}

@Preview2
@Composable
fun ClipboardBarSingleItemPreview() {
    val singleEntry = ClipboardClip.Paths(
        origin = Workspace.Id(UUID.randomUUID()),
        mode = ClipboardClip.Paths.Mode.COPY,
        paths = listOf(
            LocalPath.build("/storage/emulated/0/Documents/important_document.pdf"),
        ),
        clippedAt = Clock.System.now() - 2.minutes,
    )

    PreviewWrapper {
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
        ) {
            ClipboardBar(
                clipboardEntries = listOf(singleEntry),
                onPasteClick = {},
                onRemoveClick = {},
            )
        }
    }
}

@Preview2
@Composable
fun ClipboardBarExpandedPreview() {
    val mockEntries = listOf(
        ClipboardClip.Paths(
            origin = Workspace.Id(UUID.randomUUID()),
            mode = ClipboardClip.Paths.Mode.COPY,
            paths = listOf(
                LocalPath.build("/storage/emulated/0/Pictures/photo1.jpg"),
                LocalPath.build("/storage/emulated/0/Pictures/photo2.jpg"),
                LocalPath.build("/storage/emulated/0/Pictures/photo3.jpg"),
            ),
            clippedAt = Clock.System.now() - 5.minutes,
        ),
        ClipboardClip.Paths(
            origin = Workspace.Id(UUID.randomUUID()),
            mode = ClipboardClip.Paths.Mode.CUT,
            paths = listOf(
                LocalPath.build("/storage/emulated/0/Documents/report.pdf"),
            ),
            clippedAt = Clock.System.now() - 2.minutes,
        ),
        ClipboardClip.Paths(
            origin = Workspace.Id(UUID.randomUUID()),
            mode = ClipboardClip.Paths.Mode.COPY,
            paths = listOf(
                LocalPath.build("/storage/emulated/0/Downloads/app.apk"),
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
            ClipboardBar(
                initialExpanded = true,
                clipboardEntries = mockEntries,
                onPasteClick = {},
                onRemoveClick = {},
                onClearAll = {},
            )
        }
    }
}