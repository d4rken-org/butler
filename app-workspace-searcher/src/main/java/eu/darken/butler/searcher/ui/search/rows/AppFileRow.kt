package eu.darken.butler.searcher.ui.search.rows

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.metadata.FileType
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

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
    val (iconVector, tint) = getFileIconAndTint(data)
    
    Icon(
        imageVector = iconVector,
        contentDescription = data.fileType.name,
        tint = tint,
        modifier = Modifier.size(22.dp)
    )
}

@Preview2
@Composable
private fun AppFileRowPreview() {
    PreviewWrapper {
        Column {
            AppFileRow(
                data = FileRowData(
                    name = "signal-android.apk",
                    path = "/storage/emulated/0/Download/signal-android.apk",
                    fileType = FileType.FILE,
                    size = 1024 * 1024 * 47,
                    modifiedAt = Clock.System.now() - 1800.seconds,
                    metadata = mapOf(
                        "Package" to "org.thoughtcrime.securesms",
                        "Version" to "6.42.3",
                        "Target SDK" to "34",
                        "Min SDK" to "26"
                    )
                )
            )
            
            AppFileRow(
                data = FileRowData(
                    name = "butler-app.aab",
                    path = "/storage/emulated/0/Android/data/eu.darken.butler/butler-app.aab",
                    fileType = FileType.FILE,
                    size = 1024 * 1024 * 12,
                    modifiedAt = Clock.System.now() - 3600.seconds,
                    metadata = mapOf(
                        "Package" to "eu.darken.butler",
                        "Version" to "1.0.0"
                    )
                )
            )
        }
    }
}