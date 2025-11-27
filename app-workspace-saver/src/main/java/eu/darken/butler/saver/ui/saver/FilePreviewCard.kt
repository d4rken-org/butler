package eu.darken.butler.saver.ui.saver

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.InsertDriveFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.saver.R
import eu.darken.butler.saver.core.ContentUriHelper

@Composable
internal fun FilePreviewCard(
    modifier: Modifier = Modifier,
    sourceInfo: ContentUriHelper.SourceInfo?,
) {
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.saver_preview_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (sourceInfo != null) {
                    val isImage = sourceInfo.mimeType?.startsWith("image/") == true
                    if (isImage) {
                        AsyncImage(
                            model = sourceInfo.uri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Icon(
                            modifier = Modifier.size(64.dp),
                            imageVector = Icons.TwoTone.InsertDriveFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Preview2
@Composable
private fun FilePreviewCardLoadingPreview() {
    PreviewWrapper {
        FilePreviewCard(sourceInfo = null)
    }
}

@Preview2
@Composable
private fun FilePreviewCardImagePreview() {
    PreviewWrapper {
        FilePreviewCard(
            sourceInfo = ContentUriHelper.SourceInfo(
                uri = Uri.parse("content://example/image.jpg"),
                displayName = "image.jpg",
                mimeType = "image/jpeg",
                size = 1024 * 1024,
                isAccessible = true,
            )
        )
    }
}

@Preview2
@Composable
private fun FilePreviewCardNonImagePreview() {
    PreviewWrapper {
        FilePreviewCard(
            sourceInfo = ContentUriHelper.SourceInfo(
                uri = Uri.parse("content://example/document.pdf"),
                displayName = "document.pdf",
                mimeType = "application/pdf",
                size = 2 * 1024 * 1024,
                isAccessible = true,
            )
        )
    }
}
