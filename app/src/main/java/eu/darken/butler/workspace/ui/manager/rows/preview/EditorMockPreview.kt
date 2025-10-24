package eu.darken.butler.workspace.ui.manager.rows.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

private data class EditorPreviewData(
    val fileName: String? = "MainActivity.kt",
    val contentSnippet: String? = """
        package com.example.app
        
        import android.os.Bundle
        import androidx.activity.ComponentActivity
        
        class MainActivity : ComponentActivity() {
            override fun onCreate(savedInstanceState: Bundle?) {
    """.trimIndent(),
)

@Composable
fun EditorMockPreview(
    modifier: Modifier = Modifier,
) {
    val data = EditorPreviewData()

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        CodeSnippet(lines = data.contentSnippet?.lines() ?: emptyList())

        data.fileName?.let { fileName ->
            FileNameBadge(
                fileName = fileName,
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }
    }
}

@Composable
private fun CodeSnippet(
    lines: List<String>,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        lines.take(6).forEachIndexed { index, line ->
            CodeLine(
                lineNumber = index + 1,
                content = line
            )
        }
    }
}

@Composable
private fun CodeLine(
    lineNumber: Int,
    content: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "$lineNumber",
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.width(12.dp)
        )
        Text(
            text = content,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FileNameBadge(
    fileName: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(4.dp)),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
    ) {
        Text(
            text = fileName,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 8.sp),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

@Preview2
@Composable
private fun EditorMockPreviewPreview() {
    PreviewWrapper {
        EditorMockPreview()
    }
}