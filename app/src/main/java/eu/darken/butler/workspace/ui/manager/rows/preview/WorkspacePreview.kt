package eu.darken.butler.workspace.ui.manager.rows.preview

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.preview.WorkspacePreviewModel
import eu.darken.butler.workspace.ui.manager.rows.PaneBadge
import eu.darken.butler.workspace.ui.manager.rows.StackBadge
import eu.darken.butler.workspace.ui.manager.rows.WorkspacePreviewInfoBar

object WorkspacePreviewDefaults {
    /** Card thumbnails are a fixed height, so anything standing in for a card has to match it. */
    val Height = 160.dp
}

@Composable
fun WorkspacePreview(
    modifier: Modifier = Modifier,
    workspaceId: Workspace.Id,
    type: Workspace.Type,
    livePreview: Boolean = true,
    paneNumber: Int? = null,
    shouldShowBadge: Boolean = false,
    stackDepth: Int = 0,
    contentAlpha: Float = 1f,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier = modifier) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(WorkspacePreviewDefaults.Height),
            shape = RectangleShape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Only the thumbnail dims - the overlay below stays at full opacity so a paused
                // card keeps its identity text legible
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(contentAlpha),
                ) {
                    if (livePreview) {
                        SubcomposeAsyncImage(
                            model = WorkspacePreviewModel(workspaceId),
                            contentDescription = "Workspace preview",
                            modifier = Modifier.fillMaxSize(),
                            alignment = Alignment.TopCenter,
                            contentScale = ContentScale.FillWidth,
                            loading = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.align(Alignment.Center)
                                    )
                                }
                            },
                            error = {
                                Crossfade(
                                    targetState = type,
                                    label = "WorkspacePreview"
                                ) { workspaceType ->
                                    WorkspaceMockPreview(type = workspaceType)
                                }
                            }
                        )
                    } else {
                        WorkspaceMockPreview(type = type)
                    }
                }

                overlay()
            }
        }

        if (shouldShowBadge && paneNumber != null) {
            PaneBadge(
                paneNumber = paneNumber,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            )
        }

        if (stackDepth > 0) {
            StackBadge(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspacePreviewExplorerPreview() {
    WorkspacePreview(
        workspaceId = Workspace.Id(),
        type = Workspace.Type.EXPLORER,
        shouldShowBadge = true,
        paneNumber = 1,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspacePreviewSearcherPreview() {
    WorkspacePreview(
        workspaceId = Workspace.Id(),
        type = Workspace.Type.SEARCHER,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspacePreviewEditorPreview() {
    WorkspacePreview(
        workspaceId = Workspace.Id(),
        type = Workspace.Type.EDITOR,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspacePreviewTemplatesPreview() {
    WorkspacePreview(
        workspaceId = Workspace.Id(),
        type = Workspace.Type.TEMPLATES,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspacePreviewWithInfoBarPreview() {
    WorkspacePreview(
        workspaceId = Workspace.Id(),
        type = Workspace.Type.EXPLORER,
        livePreview = false,
    ) {
        WorkspacePreviewInfoBar(
            modifier = Modifier.align(Alignment.BottomStart),
            primary = "Trash".toCaString(),
            secondary = "Recover deleted files".toCaString(),
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspacePreviewStackedPreview() {
    WorkspacePreview(
        workspaceId = Workspace.Id(),
        type = Workspace.Type.APP_DETAILS,
        livePreview = false,
        shouldShowBadge = true,
        paneNumber = 0,
        stackDepth = 1,
    ) {
        WorkspacePreviewInfoBar(
            modifier = Modifier.align(Alignment.BottomStart),
            primary = "Butler".toCaString(),
            secondary = "eu.darken.butler".toCaString(),
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspacePreviewDimmedContentPreview() {
    WorkspacePreview(
        workspaceId = Workspace.Id(),
        type = Workspace.Type.SEARCHER,
        livePreview = false,
        contentAlpha = 0.4f,
    ) {
        WorkspacePreviewInfoBar(
            modifier = Modifier.align(Alignment.BottomStart),
            primary = "*.log".toCaString(),
            secondary = "Device storage, SD card".toCaString(),
        )
    }
}
