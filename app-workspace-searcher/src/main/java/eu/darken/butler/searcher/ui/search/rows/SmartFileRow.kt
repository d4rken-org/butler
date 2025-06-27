package eu.darken.butler.searcher.ui.search.rows

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import java.time.Instant

@Composable
fun SmartFileRow(
    data: FileRowData,
    onClick: () -> Unit = {}
) {
    when (determineFileRowType(data.name)) {
        FileRowType.Media -> MediaFileRow(data = data, onClick = onClick)
        FileRowType.App -> AppFileRow(data = data, onClick = onClick)
        else -> StandardFileRow(data = data, onClick = onClick)
    }
}

@Preview2
@Composable
private fun SmartFileRowPreview() {
    PreviewWrapper {
        Column {
            SmartFileRow(
                data = FileRowData(
                    name = "photo.jpg",
                    path = "/storage/emulated/0/Pictures/photo.jpg",
                    fileType = FileType.FILE,
                    size = 1024 * 1024 * 2,
                    modifiedAt = Instant.now().minusSeconds(3600),
                    metadata = mapOf("Resolution" to "4032x3024")
                )
            )
            
            SmartFileRow(
                data = FileRowData(
                    name = "app.apk",
                    path = "/storage/emulated/0/Download/app.apk",
                    fileType = FileType.FILE,
                    size = 1024 * 1024 * 35,
                    modifiedAt = Instant.now().minusSeconds(1800),
                    metadata = mapOf(
                        "Package" to "com.example.app",
                        "Version" to "2.1.0"
                    )
                )
            )
            
            SmartFileRow(
                data = FileRowData(
                    name = "document.txt",
                    path = "/storage/emulated/0/Documents/document.txt",
                    fileType = FileType.FILE,
                    size = 1024 * 5,
                    modifiedAt = Instant.now().minusSeconds(900)
                )
            )
            
            SmartFileRow(
                data = FileRowData(
                    name = "Downloads",
                    path = "/storage/emulated/0/Downloads",
                    fileType = FileType.DIRECTORY,
                    modifiedAt = Instant.now().minusSeconds(7200)
                )
            )
        }
    }
}