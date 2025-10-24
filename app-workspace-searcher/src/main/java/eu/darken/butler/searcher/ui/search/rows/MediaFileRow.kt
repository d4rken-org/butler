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
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.ui.search.preview.SearcherMockDataProvider

@Composable
fun MediaFileRow(
    result: SearchItem,
    onClick: () -> Unit = {}
) {
    FileRowBase(result = result, onClick = onClick) { fileResult ->
        MediaFileIcon(fileResult)

        Spacer(modifier = Modifier.width(12.dp))

        FileInfo(
            result = fileResult,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun MediaFileIcon(result: SearchItem) {
    TintedAsyncImage(
        model = result.lookup,
        contentDescription = result.fileType.name,
        modifier = Modifier.size(40.dp)
    )
}

@Preview2
@Composable
private fun MediaFileRowPreview() {
    PreviewWrapper {
        Column {
            MediaFileRow(
                result = SearcherMockDataProvider.createMockImageFile(
                    name = "vacation_photo.jpg",
                    sizeMB = 3,
                    hoursAgo = 2,
                    metadata = mapOf(
                        "Resolution" to "1920x1080",
                        "Camera" to "Pixel 8"
                    )
                )
            )

            MediaFileRow(
                result = SearcherMockDataProvider.createMockVideoFile(
                    name = "summer_video.mp4",
                    sizeMB = 25,
                    hoursAgo = 5
                )
            )

            MediaFileRow(
                result = SearcherMockDataProvider.createMockAudioFile(
                    name = "favorite_song.mp3"
                )
            )
        }
    }
}