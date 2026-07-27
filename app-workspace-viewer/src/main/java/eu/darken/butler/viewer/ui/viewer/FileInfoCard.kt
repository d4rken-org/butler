package eu.darken.butler.viewer.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.formatDate
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.viewer.R
import eu.darken.butler.viewer.core.ViewerFileInfo
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * Bottom floating card with the file's metadata. Rows for values the gateway did not provide are
 * omitted entirely - permissions and ownership are meaningless on public/SAF storage and showing
 * an empty row there would be worse than showing nothing.
 */
@Composable
fun FileInfoCard(
    modifier: Modifier = Modifier,
    fileInfo: ViewerFileInfo,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            fileInfo.size?.let {
                InfoRow(
                    label = stringResource(R.string.viewer_info_size_label),
                    value = formatFileSize(it),
                )
            }
            fileInfo.modifiedAt?.let {
                InfoRow(
                    label = stringResource(R.string.viewer_info_modified_label),
                    value = formatDate(it),
                )
            }
            fileInfo.createdAt?.let {
                InfoRow(
                    label = stringResource(R.string.viewer_info_created_label),
                    value = formatDate(it),
                )
            }
            fileInfo.imageInfo?.let { image ->
                InfoRow(
                    label = stringResource(R.string.viewer_info_format_label),
                    value = image.format,
                )
                if (image.width != null && image.height != null) {
                    InfoRow(
                        label = stringResource(R.string.viewer_info_dimensions_label),
                        value = stringResource(
                            R.string.viewer_info_dimensions_value,
                            image.width,
                            image.height,
                        ),
                    )
                }
            }
            fileInfo.permissions?.let {
                InfoRow(
                    label = stringResource(R.string.viewer_info_permissions_label),
                    value = stringResource(
                        R.string.viewer_info_permissions_value,
                        it.toReadableString(),
                        it.octal,
                    ),
                )
            }
            fileInfo.ownership?.let {
                InfoRow(
                    label = stringResource(R.string.viewer_info_owner_label),
                    value = stringResource(
                        R.string.viewer_info_owner_value,
                        it.userName ?: it.userId.toString(),
                        it.groupName ?: it.groupId.toString(),
                    ),
                )
            }
        }
    }
}

@Composable
private fun InfoRow(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(0.6f),
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FileInfoCardFullPreview() {
    FileInfoCard(
        modifier = Modifier
            .width(360.dp)
            .padding(16.dp),
        fileInfo = ViewerFileInfo(
            size = 4_812_331L,
            modifiedAt = Clock.System.now() - 2.days,
            createdAt = Clock.System.now() - 30.days,
            permissions = Permissions(0b110_100_100),
            ownership = Ownership(userId = 1000L, groupId = 1000L, userName = "media_rw", groupName = "media_rw"),
            imageInfo = ViewerFileInfo.ImageInfo(format = "image/jpeg", width = 4032, height = 3024),
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FileInfoCardWithoutPosixMetadataPreview() {
    FileInfoCard(
        modifier = Modifier
            .width(360.dp)
            .padding(16.dp),
        fileInfo = ViewerFileInfo(
            size = 128_004L,
            modifiedAt = Clock.System.now(),
            imageInfo = ViewerFileInfo.ImageInfo(format = "image/svg+xml"),
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FileInfoCardMinimalPreview() {
    FileInfoCard(
        modifier = Modifier
            .width(360.dp)
            .padding(16.dp),
        fileInfo = ViewerFileInfo(size = 0L),
    )
}
