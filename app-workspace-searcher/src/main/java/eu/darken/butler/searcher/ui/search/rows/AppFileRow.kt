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
fun AppFileRow(
    data: FileRowData,
    onClick: () -> Unit = {}
) {
    FileRowBase(data = data, onClick = onClick) { fileData ->
        AppFileIcon(fileData)

        Spacer(modifier = Modifier.width(12.dp))

        FileInfo(
            data = fileData,
            showMetadata = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun AppFileIcon(data: FileRowData) {
    TintedAsyncImage(
        model = data.lookup,
        contentDescription = data.fileType.name,
        modifier = Modifier.size(40.dp)
    )
}

@Preview2
@Composable
private fun AppFileRowPreview() {
    PreviewWrapper {
        Column {
            AppFileRow(
                data = SearcherMockDataProvider.createMockApkFile(
                    name = "signal-android.apk",
                    sizeMB = 47,
                    hoursAgo = 1,
                    metadata = mapOf(
                        "Package" to "org.thoughtcrime.securesms",
                        "Version" to "6.42.3",
                        "Target SDK" to "34",
                        "Min SDK" to "26"
                    )
                )
            )

            AppFileRow(
                data = SearcherMockDataProvider.createMockApkFile(
                    name = "butler-app.aab",
                    sizeMB = 12,
                    hoursAgo = 1,
                    metadata = mapOf(
                        "Package" to "eu.darken.butler",
                        "Version" to "1.0.0"
                    )
                )
            )
        }
    }
}