package eu.darken.butler.searcher.ui.search.rows

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.TintedAsyncImage
import eu.darken.butler.searcher.ui.search.preview.SearcherMockDataProvider

@Composable
fun StandardFileRow(
    data: FileRowData,
    onClick: () -> Unit = {},
    icon: @Composable () -> Unit = { StandardFileIcon(data) }
) {
    FileRowBase(data = data, onClick = onClick) { fileData ->
        icon()

        Spacer(modifier = Modifier.width(12.dp))

        FileInfo(
            data = fileData,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun StandardFileIcon(data: FileRowData) {
    TintedAsyncImage(
        model = data.lookup,
        contentDescription = data.fileType.name,
        modifier = Modifier.size(40.dp)
    )
}


@Preview2
@Composable
private fun StandardFileRowPreview() {
    PreviewWrapper {
        Column {
            StandardFileRow(
                data = SearcherMockDataProvider.createMockPdfFile(
                    name = "document.pdf",
                    sizeMB = 1
                )
            )

            StandardFileRow(
                data = SearcherMockDataProvider.createMockDirectory(
                    name = "Pictures",
                    hoursAgo = 24
                )
            )

            StandardFileRow(
                data = SearcherMockDataProvider.createMockConfigFile(
                    name = "config.json"
                )
            )
        }
    }
}