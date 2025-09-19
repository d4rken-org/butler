package eu.darken.butler.workspace.ui.manager.rows.preview

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.preview.EditorPreviewData
import eu.darken.butler.workspace.core.preview.ExplorerPreviewData
import eu.darken.butler.workspace.core.preview.ExplorerPreviewItem
import eu.darken.butler.workspace.core.preview.PreviewData
import eu.darken.butler.workspace.core.preview.SearcherPreviewData
import eu.darken.butler.workspace.core.preview.TemplatesPreviewData

@Composable
fun WorkspacePreview(
    modifier: Modifier = Modifier,
    type: Workspace.Type,
    previewData: PreviewData? = null,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Crossfade(
            targetState = type,
            label = "WorkspacePreview"
        ) { workspaceType ->
            when (workspaceType) {
                Workspace.Type.EXPLORER -> ExplorerPreview(data = previewData as? ExplorerPreviewData)
                Workspace.Type.SEARCHER -> SearcherPreview(data = previewData as? SearcherPreviewData)
                Workspace.Type.EDITOR -> EditorPreview(data = previewData as? EditorPreviewData)
                Workspace.Type.TEMPLATES -> TemplatesPreview(data = previewData as? TemplatesPreviewData)
            }
        }
    }
}

@Preview2
@Composable
private fun WorkspacePreviewExplorerPreview() {
    PreviewWrapper {
        WorkspacePreview(
            type = Workspace.Type.EXPLORER,
            previewData = ExplorerPreviewData(
                currentPath = "/storage/emulated/0",
                items = listOf(
                    ExplorerPreviewItem("Android", true),
                    ExplorerPreviewItem("DCIM", true),
                    ExplorerPreviewItem("Download", true),
                    ExplorerPreviewItem("readme.txt", false),
                )
            )
        )
    }
}

@Preview2
@Composable
private fun WorkspacePreviewSearcherPreview() {
    PreviewWrapper {
        WorkspacePreview(
            type = Workspace.Type.SEARCHER,
            previewData = SearcherPreviewData(
                query = "*.pdf",
                results = listOf(
                    "document.pdf",
                    "report_2024.pdf",
                    "invoice.pdf"
                ),
                resultCount = 42
            )
        )
    }
}

@Preview2
@Composable
private fun WorkspacePreviewEditorPreview() {
    PreviewWrapper {
        WorkspacePreview(
            type = Workspace.Type.EDITOR,
            previewData = EditorPreviewData(
                fileName = "MainActivity.kt",
                contentSnippet = """
                    package com.example.app
                    
                    import android.os.Bundle
                    import androidx.activity.ComponentActivity
                    
                    class MainActivity : ComponentActivity() {
                        override fun onCreate(savedInstanceState: Bundle?) {
                """.trimIndent()
            )
        )
    }
}

@Preview2
@Composable
private fun WorkspacePreviewTemplatesPreview() {
    PreviewWrapper {
        WorkspacePreview(
            type = Workspace.Type.TEMPLATES,
            previewData = TemplatesPreviewData(templateCount = 5)
        )
    }
}