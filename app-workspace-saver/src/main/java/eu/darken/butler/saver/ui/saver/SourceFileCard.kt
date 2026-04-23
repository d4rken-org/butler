package eu.darken.butler.saver.ui.saver

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.CheckCircle
import androidx.compose.material.icons.twotone.Error
import androidx.compose.material.icons.twotone.InsertDriveFile
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.saver.R
import eu.darken.butler.saver.core.ContentUriHelper

@Composable
internal fun SourceFileCard(
    modifier: Modifier = Modifier,
    sourceInfo: ContentUriHelper.SourceInfo?,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                modifier = Modifier.size(24.dp),
                imageVector = Icons.TwoTone.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sourceInfo?.displayName ?: stringResource(R.string.saver_loading),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sourceInfo != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        sourceInfo.size?.let { size ->
                            Text(
                                text = formatFileSize(bytes = size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        sourceInfo.mimeType?.let { mimeType ->
                            Text(
                                text = mimeType,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (sourceInfo?.isAccessible == true) {
                Icon(
                    imageVector = Icons.TwoTone.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else if (sourceInfo?.isAccessible == false) {
                Icon(
                    imageVector = Icons.TwoTone.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SourceFileCardLoadingPreview() {
    SourceFileCard(sourceInfo = null)
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SourceFileCardAccessiblePreview() {
    SourceFileCard(
        sourceInfo = ContentUriHelper.SourceInfo(
            uri = Uri.parse("content://example/image.jpg"),
            displayName = "vacation_photo_2024.jpg",
            mimeType = "image/jpeg",
            size = 3_500_000,
            isAccessible = true,
        )
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SourceFileCardInaccessiblePreview() {
    SourceFileCard(
        sourceInfo = ContentUriHelper.SourceInfo(
            uri = Uri.parse("content://example/document.pdf"),
            displayName = "important_document.pdf",
            mimeType = "application/pdf",
            size = 1_200_000,
            isAccessible = false,
        )
    )
}
