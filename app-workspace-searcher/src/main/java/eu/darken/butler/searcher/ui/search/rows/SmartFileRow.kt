package eu.darken.butler.searcher.ui.search.rows

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.searcher.ui.search.preview.SearcherMockDataProvider

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
                data = SearcherMockDataProvider.createMockImageFile(
                    name = "photo.jpg",
                    sizeMB = 2,
                    hoursAgo = 1,
                    metadata = mapOf("Resolution" to "4032x3024")
                )
            )

            SmartFileRow(
                data = SearcherMockDataProvider.createMockApkFile(
                    name = "app.apk",
                    sizeMB = 35
                )
            )

            SmartFileRow(
                data = SearcherMockDataProvider.createMockTextFile(
                    name = "document.txt",
                    sizeKB = 5
                )
            )

            SmartFileRow(
                data = SearcherMockDataProvider.createMockDirectory(
                    name = "Downloads",
                    path = "/storage/emulated/0/Downloads",
                    hoursAgo = 2
                )
            )
        }
    }
}