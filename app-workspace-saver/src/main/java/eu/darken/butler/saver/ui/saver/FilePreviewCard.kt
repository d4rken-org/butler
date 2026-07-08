package eu.darken.butler.saver.ui.saver

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.previews.SharedContentPreview
import eu.darken.butler.saver.R
import eu.darken.butler.saver.core.ContentUriHelper

@Composable
internal fun FilePreviewCard(
    modifier: Modifier = Modifier,
    sourceInfo: ContentUriHelper.SourceInfo?,
) {
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)) {
            Text(
                text = stringResource(R.string.saver_preview_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (sourceInfo != null) {
                    val mime = sourceInfo.mimeType
                    val name = sourceInfo.displayName
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val isMedia = mime?.startsWith("image/") == true || mime?.startsWith("video/") == true
                    val isApk = mime == "application/vnd.android.package-archive" || ext == "apk"
                    val isPdf = mime == "application/pdf" || ext == "pdf"
                    val typeIcon = fileTypeIcon(mime, name)

                    // Don't open an expired source; images/videos preview zero-copy via the content URI,
                    // apk/pdf via the no-copy SharedContentPreview fetcher. Anything else -> type glyph.
                    val model: Any? = when {
                        !sourceInfo.isAccessible -> null
                        isMedia -> sourceInfo.uri
                        isApk || isPdf -> SharedContentPreview(sourceInfo.uri, mime, name, sourceInfo.size)
                        else -> null
                    }

                    if (model != null) {
                        SubcomposeAsyncImage(
                            model = model,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                            loading = { CircularProgressIndicator() },
                            error = { TypeGlyph(typeIcon) },
                        )
                    } else {
                        TypeGlyph(typeIcon)
                    }
                } else {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun TypeGlyph(icon: ImageVector) {
    Icon(
        modifier = Modifier.size(64.dp),
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FilePreviewCardLoadingPreview() {
    FilePreviewCard(sourceInfo = null)
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FilePreviewCardImagePreview() {
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

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FilePreviewCardNonImagePreview() {
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
