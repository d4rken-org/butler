package eu.darken.butler.workspace.ui.manager.rows.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.InsertDriveFile
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper


private data class ExplorerPreviewData(
    val currentPath: String? = "/storage/emulated/0",
    val items: List<Item> = listOf(
        Item("Android", true),
        Item("DCIM", true),
        Item("Download", true),
        Item("readme.txt", false),
    ),
) {
    data class Item(
        val name: String,
        val isDirectory: Boolean,
    )

}

@Composable
fun ExplorerMockPreview(
    modifier: Modifier = Modifier,
) {
    val data = ExplorerPreviewData()
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        data.items.forEach { item -> FileItemRow(item = item) }
    }
}

@Composable
private fun FileItemRow(
    item: ExplorerPreviewData.Item,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = if (item.isDirectory) {
                Icons.TwoTone.Folder
            } else {
                Icons.AutoMirrored.TwoTone.InsertDriveFile
            },
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = if (item.isDirectory) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            }
        )
        Text(
            text = item.name,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Preview2
@Composable
private fun ExplorerMockPreviewPreview() {
    PreviewWrapper {
        ExplorerMockPreview()
    }
}