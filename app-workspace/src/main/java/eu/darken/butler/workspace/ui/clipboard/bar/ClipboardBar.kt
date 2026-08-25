package eu.darken.butler.workspace.ui.clipboard.bar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.clipboard.mockFileLookup
import eu.darken.butler.common.ui.SwipeToDismissItem
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.ui.LocalWorkspaceTitles
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid
import eu.darken.butler.common.R as CommonR

@Composable
fun ClipboardBar(
    workspaceType: Workspace.Type,
    modifier: Modifier = Modifier,
    initialExpanded: Boolean = false,
    clipboardEntries: List<ClipboardClip>,
    onPasteClick: (ClipboardClip) -> Unit,
    onRemoveClick: (ClipboardClip) -> Unit,
    onEntryClick: (ClipboardClip) -> Unit = {},
    onClearAll: () -> Unit = {},
) {
    // Preserve expansion state across clipboard changes
    var isExpanded by remember {
        mutableStateOf(initialExpanded)
    }

    // State for cascading clear all animation
    var clearAllAnimationTrigger by remember { mutableLongStateOf(0L) }

    // Handle cascading clear all animation
    LaunchedEffect(clearAllAnimationTrigger) {
        if (clearAllAnimationTrigger > 0L) {
            // Wait for all swipe animations to complete before clearing
            val totalAnimationTime = (clipboardEntries.size * 200L) + 500L
            delay(totalAnimationTime)
            onClearAll()
            clearAllAnimationTrigger = 0L
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
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        ) {
            Column(
                modifier = Modifier.animateContentSize(
                    animationSpec = tween(durationMillis = 300)
                )
            ) {
                ClipboardBarHeader(
                    isExpanded = isExpanded,
                    entryCount = clipboardEntries.size,
                    onExpandClick = { isExpanded = !isExpanded },
                    onClearAllClick = {
                        if (isExpanded) {
                            clearAllAnimationTrigger = System.currentTimeMillis()
                        } else {
                            onClearAll()
                        }
                    },
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 32.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )

                // Additional entries (when expanded) - already in correct order (oldest first)
                additionalEntries.forEachIndexed { index, entry ->
                    key(entry.id) {
                        SwipeToDismissEntry(
                            entry = entry,
                            workspaceType = workspaceType,
                            onPasteClick = { onPasteClick(entry) },
                            onEntryClick = { onEntryClick(entry) },
                            onRemoveClick = { onRemoveClick(entry) },
                            showOrigin = true, // Show origin for expanded entries
                            triggerDismiss = clearAllAnimationTrigger,
                            dismissDelay = index * 200L, // Cascade delay
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
                            workspaceType = workspaceType,
                            onPasteClick = { onPasteClick(entry) },
                            onEntryClick = { onEntryClick(entry) },
                            onRemoveClick = { onRemoveClick(entry) },
                            showOrigin = isExpanded,
                            triggerDismiss = clearAllAnimationTrigger,
                            dismissDelay = additionalEntries.size * 200L, // Latest entry has longest delay
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SwipeToDismissEntry(
    modifier: Modifier = Modifier,
    entry: ClipboardClip,
    workspaceType: Workspace.Type,
    onPasteClick: () -> Unit,
    onEntryClick: () -> Unit,
    onRemoveClick: () -> Unit,
    showOrigin: Boolean = false,
    triggerDismiss: Long = 0L,
    dismissDelay: Long = 0L,
) {
    SwipeToDismissItem(
        modifier = modifier,
        onDismiss = onRemoveClick,
        dismissThreshold = 0.5f,
        backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        verticalPadding = 8,
        programmaticDismissTrigger = triggerDismiss,
        programmaticDismissDelay = dismissDelay,
        dismissContent = {
            Icon(
                imageVector = Icons.TwoTone.Close,
                contentDescription = stringResource(CommonR.string.general_dismiss_action),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(CommonR.string.general_dismiss_action),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    ) {
        ClipboardEntryRow(
            entry = entry,
            workspaceType = workspaceType,
            onPasteClick = onPasteClick,
            onEntryClick = onEntryClick,
            showOrigin = showOrigin,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
fun ClipboardBarPreview() {
    val mockEntries = listOf(
        ClipboardClip.Paths(
            origin = Workspace.Id(Uuid.random()),
            mode = ClipboardClip.Paths.Mode.COPY,
            paths = listOf(
                mockFileLookup("/storage/emulated/0/Pictures/photo1.jpg"),
                mockFileLookup("/storage/emulated/0/Pictures/photo2.jpg"),
                mockFileLookup("/storage/emulated/0/Pictures/photo3.jpg"),
            ),
            clippedAt = Clock.System.now() - 5.minutes,
        ),
        ClipboardClip.Paths(
            origin = Workspace.Id(Uuid.random()),
            mode = ClipboardClip.Paths.Mode.CUT,
            paths = listOf(
                mockFileLookup("/storage/emulated/0/Documents/report.pdf"),
            ),
            clippedAt = Clock.System.now() - 2.minutes,
        ),
    )

    PreviewWrapper {
        ClipboardBar(
            workspaceType = Workspace.Type.EXPLORER,
            clipboardEntries = mockEntries,
            onPasteClick = {},
            onRemoveClick = {},
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
fun ClipboardBarSingleItemPreview() {
    val singleEntry = ClipboardClip.Paths(
        origin = Workspace.Id(Uuid.random()),
        mode = ClipboardClip.Paths.Mode.COPY,
        paths = listOf(
            mockFileLookup("/storage/emulated/0/Documents/important_document.pdf"),
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
                workspaceType = Workspace.Type.SEARCHER,
                clipboardEntries = listOf(singleEntry),
                onPasteClick = {},
                onRemoveClick = {},
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
fun ClipboardBarExpandedPreview() {
    val picturesOrigin = Workspace.Id(Uuid.random())
    val documentsOrigin = Workspace.Id(Uuid.random())
    val closedOrigin = Workspace.Id(Uuid.random())
    val mockEntries = listOf(
        ClipboardClip.Paths(
            origin = picturesOrigin,
            mode = ClipboardClip.Paths.Mode.COPY,
            paths = listOf(
                mockFileLookup("/storage/emulated/0/Pictures/photo1.jpg"),
                mockFileLookup("/storage/emulated/0/Pictures/photo2.jpg"),
                mockFileLookup("/storage/emulated/0/Pictures/photo3.jpg"),
            ),
            clippedAt = Clock.System.now() - 5.minutes,
        ),
        ClipboardClip.Paths(
            origin = documentsOrigin,
            mode = ClipboardClip.Paths.Mode.CUT,
            paths = listOf(
                mockFileLookup("/storage/emulated/0/Documents/report.pdf"),
            ),
            clippedAt = Clock.System.now() - 2.minutes,
        ),
        ClipboardClip.Paths(
            origin = closedOrigin,
            mode = ClipboardClip.Paths.Mode.COPY,
            paths = listOf(
                mockFileLookup("/storage/emulated/0/Downloads/app.apk"),
            ),
            clippedAt = Clock.System.now() - 1.minutes,
        ),
    )
    val titles = mapOf(
        picturesOrigin to "Pictures",
        documentsOrigin to "Documents",
    )

    PreviewWrapper {
        CompositionLocalProvider(LocalWorkspaceTitles provides titles) {
            Box(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.background)
                    .padding(16.dp)
            ) {
                ClipboardBar(
                    workspaceType = Workspace.Type.EXPLORER,
                    initialExpanded = true,
                    clipboardEntries = mockEntries,
                    onPasteClick = {},
                    onRemoveClick = {},
                    onClearAll = {},
                )
            }
        }
    }
}