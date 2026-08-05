package eu.darken.butler.workspace.ui.manager.rows.preview

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.workspace.core.Workspace

@Composable
fun WorkspaceMockPreview(
    modifier: Modifier = Modifier,
    type: Workspace.Type,
) {
    when (type) {
        Workspace.Type.EXPLORER -> ExplorerMockPreview(modifier = modifier)
        Workspace.Type.SEARCHER -> SearcherMockPreview(modifier = modifier)
        Workspace.Type.EDITOR -> EditorMockPreview(modifier = modifier)
        Workspace.Type.TEMPLATES -> TemplatesMockPreview(modifier = modifier)
        Workspace.Type.APPS -> AppsMockPreview(modifier = modifier)
        Workspace.Type.APP_DETAILS -> AppsMockPreview(modifier = modifier)
        Workspace.Type.SAVER -> EditorMockPreview(modifier = modifier)
        Workspace.Type.DEVELOPER -> DeveloperMockPreview(modifier = modifier)
        Workspace.Type.HISTORY -> DeveloperMockPreview(modifier = modifier)
        Workspace.Type.BUG_REPORT -> DeveloperMockPreview(modifier = modifier)
        Workspace.Type.VIEWER -> ExplorerMockPreview(modifier = modifier)
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceMockPreviewExplorerPreview() {
    WorkspaceMockPreview(
        modifier = Modifier.height(120.dp),
        type = Workspace.Type.EXPLORER,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceMockPreviewEditorPreview() {
    WorkspaceMockPreview(
        modifier = Modifier.height(120.dp),
        type = Workspace.Type.EDITOR,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceMockPreviewAliasTypesPreview() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
    ) {
        WorkspaceMockPreview(modifier = Modifier.weight(1f), type = Workspace.Type.SAVER)
        WorkspaceMockPreview(modifier = Modifier.weight(1f), type = Workspace.Type.VIEWER)
        WorkspaceMockPreview(modifier = Modifier.weight(1f), type = Workspace.Type.APP_DETAILS)
    }
}
