package eu.darken.butler.searcher.ui.search.items.rows

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.ui.search.preview.SearcherMockDataProvider

// Extension lists for file type detection
private val mediaExtensions = setOf(
    "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg",
    "mp4", "avi", "mkv", "mov", "wmv", "flv", "webm",
    "mp3", "wav", "flac", "aac", "ogg", "m4a"
)

private val appExtensions = setOf("apk", "aab")

@Composable
fun SmartFileRow(
    result: SearchItem,
    onClick: () -> Unit = {}
) {
    // Type-based dispatch - directories get standard row
    when (result) {
        is SearchItem.Directory -> StandardFileRow(result = result, onClick = onClick)
        is SearchItem.File -> {
            // Extension-based dispatch for files (will become type-based when we have ApkFile, ImageFile, etc.)
            val extension = result.name.substringAfterLast('.', "").lowercase()
            when {
                extension in mediaExtensions -> MediaFileRow(result = result, onClick = onClick)
                extension in appExtensions -> AppFileRow(result = result, onClick = onClick)
                else -> StandardFileRow(result = result, onClick = onClick)
            }
        }
    }
}

@Preview2
@Composable
private fun SmartFileRowPreview() {
    PreviewWrapper {
        Column {
            SmartFileRow(
                result = SearcherMockDataProvider.createMockImageFile(
                    name = "photo.jpg",
                    sizeMB = 2,
                    hoursAgo = 1,
                    metadata = mapOf("Resolution" to "4032x3024")
                )
            )

            SmartFileRow(
                result = SearcherMockDataProvider.createMockApkFile(
                    name = "app.apk",
                    sizeMB = 35
                )
            )

            SmartFileRow(
                result = SearcherMockDataProvider.createMockTextFile(
                    name = "document.txt",
                    sizeKB = 5
                )
            )

            SmartFileRow(
                result = SearcherMockDataProvider.createMockDirectory(
                    name = "Downloads",
                    path = "/storage/emulated/0/Downloads",
                    hoursAgo = 2
                )
            )
        }
    }
}