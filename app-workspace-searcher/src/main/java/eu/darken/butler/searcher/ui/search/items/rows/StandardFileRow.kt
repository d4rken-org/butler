package eu.darken.butler.searcher.ui.search.items.rows

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
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.ui.search.preview.SearcherMockDataProvider

@Composable
fun StandardFileRow(
    result: SearchItem,
    onClick: () -> Unit = {},
    icon: @Composable () -> Unit = { StandardFileIcon(result) }
) {
    FileRowBase(result = result, onClick = onClick) { fileResult ->
        icon()

        Spacer(modifier = Modifier.width(12.dp))

        FileInfo(
            result = fileResult,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun StandardFileIcon(result: SearchItem) {
    TintedAsyncImage(
        model = result.lookup,
        contentDescription = result.fileType.name,
        modifier = Modifier.size(40.dp)
    )
}


@Preview2
@Composable
private fun StandardFileRowPreview() {
    PreviewWrapper {
        Column {
            StandardFileRow(
                result = SearcherMockDataProvider.createMockPdfFile(
                    name = "document.pdf",
                    sizeMB = 1
                )
            )

            StandardFileRow(
                result = SearcherMockDataProvider.createMockDirectory(
                    name = "Pictures",
                    hoursAgo = 24
                )
            )

            StandardFileRow(
                result = SearcherMockDataProvider.createMockConfigFile(
                    name = "config.json"
                )
            )
        }
    }
}