package eu.darken.butler.workspace.ui.manager.rows.preview

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.preview.WorkspacePreviewModel
import eu.darken.butler.workspace.ui.manager.rows.PaneBadge

@Composable
fun WorkspacePreview(
    modifier: Modifier = Modifier,
    workspaceId: Workspace.Id,
    type: Workspace.Type,
    livePreview: Boolean = true,
    paneNumber: Int? = null,
    shouldShowBadge: Boolean = false,
) {
    Box(modifier = modifier) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
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
                                when (workspaceType) {
                                    Workspace.Type.EXPLORER -> ExplorerMockPreview()
                                    Workspace.Type.SEARCHER -> SearcherMockPreview()
                                    Workspace.Type.EDITOR -> EditorMockPreview()
                                    Workspace.Type.TEMPLATES -> TemplatesMockPreview()
                                    Workspace.Type.APPS -> AppsMockPreview()
                                Workspace.Type.APP_DETAILS -> AppsMockPreview() // Reuse apps preview for now
                                }
                            }
                        }
                    )
                } else {
                    when (type) {
                        Workspace.Type.EXPLORER -> ExplorerMockPreview()
                        Workspace.Type.SEARCHER -> SearcherMockPreview()
                        Workspace.Type.EDITOR -> EditorMockPreview()
                        Workspace.Type.TEMPLATES -> TemplatesMockPreview()
                        Workspace.Type.APPS -> AppsMockPreview()
                    Workspace.Type.APP_DETAILS -> AppsMockPreview() // Reuse apps preview for now
                    }
                }
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
    }
}

@Preview2
@Composable
private fun WorkspacePreviewExplorerPreview() {
    PreviewWrapper {
        WorkspacePreview(
            workspaceId = Workspace.Id(),
            type = Workspace.Type.EXPLORER,
            shouldShowBadge = true,
            paneNumber = 1,
        )
    }
}

@Preview2
@Composable
private fun WorkspacePreviewSearcherPreview() {
    PreviewWrapper {
        WorkspacePreview(
            workspaceId = Workspace.Id(),
            type = Workspace.Type.SEARCHER,
        )
    }
}

@Preview2
@Composable
private fun WorkspacePreviewEditorPreview() {
    PreviewWrapper {
        WorkspacePreview(
            workspaceId = Workspace.Id(),
            type = Workspace.Type.EDITOR,
        )
    }
}

@Preview2
@Composable
private fun WorkspacePreviewTemplatesPreview() {
    PreviewWrapper {
        WorkspacePreview(
            workspaceId = Workspace.Id(),
            type = Workspace.Type.TEMPLATES,
        )
    }
}