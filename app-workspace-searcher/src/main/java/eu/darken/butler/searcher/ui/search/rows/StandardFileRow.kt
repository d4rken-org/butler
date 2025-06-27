package eu.darken.butler.searcher.ui.search.rows

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import java.time.Instant

@Composable
fun StandardFileRow(
    data: FileRowData,
    onClick: () -> Unit = {},
    icon: @Composable () -> Unit = { StandardFileIcon(data) }
) {
    FileRowBase(data = data, onClick = onClick) { fileData ->
        icon()

        Spacer(modifier = Modifier.width(16.dp))

        FileInfo(
            data = fileData,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun StandardFileIcon(data: FileRowData) {
    val (iconVector, tint) = getFileIconAndTint(data)
    
    Icon(
        imageVector = iconVector,
        contentDescription = data.fileType.name,
        tint = tint,
        modifier = Modifier.size(24.dp)
    )
}


@Preview2
@Composable
private fun StandardFileRowPreview() {
    PreviewWrapper {
        Column {
            StandardFileRow(
                data = FileRowData(
                    name = "document.pdf",
                    path = "/storage/emulated/0/Downloads/document.pdf",
                    fileType = FileType.FILE,
                    size = 1024 * 512,
                    modifiedAt = Instant.now().minusSeconds(3600)
                )
            )
            
            StandardFileRow(
                data = FileRowData(
                    name = "Pictures",
                    path = "/storage/emulated/0/Pictures",
                    fileType = FileType.DIRECTORY,
                    modifiedAt = Instant.now().minusSeconds(86400)
                )
            )
            
            StandardFileRow(
                data = FileRowData(
                    name = "config.json",
                    path = "/storage/emulated/0/Android/data/eu.darken.butler/config.json",
                    fileType = FileType.FILE,
                    size = 256,
                    modifiedAt = Instant.now().minusSeconds(300)
                )
            )
        }
    }
}