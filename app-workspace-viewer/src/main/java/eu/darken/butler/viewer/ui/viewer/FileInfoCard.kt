package eu.darken.butler.viewer.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
import eu.darken.butler.common.DateTimeStyle
import eu.darken.butler.common.formatDateTime
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.viewer.R
import eu.darken.butler.viewer.core.ViewerFileInfo
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

internal data class InfoEntry(val label: String, val value: String, val pairable: Boolean)

/** Groups consecutive pairable entries two-per-row; non-pairable entries get a row of their own. */
internal fun groupInfoEntries(entries: List<InfoEntry>): List<List<InfoEntry>> {
    val rows = mutableListOf<List<InfoEntry>>()
    var pending = mutableListOf<InfoEntry>()
    entries.forEach { entry ->
        if (entry.pairable) {
            pending.add(entry)
            if (pending.size == 2) {
                rows.add(pending)
                pending = mutableListOf()
            }
        } else {
            if (pending.isNotEmpty()) {
                rows.add(pending)
                pending = mutableListOf()
            }
            rows.add(listOf(entry))
        }
    }
    if (pending.isNotEmpty()) rows.add(pending)
    return rows
}

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
    val entries = buildList<InfoEntry> {
        fileInfo.size?.let {
            add(
                InfoEntry(
                    label = stringResource(R.string.viewer_info_size_label),
                    value = formatFileSize(it),
                    pairable = true,
                ),
            )
        }
        fileInfo.modifiedAt?.let {
            add(
                InfoEntry(
                    label = stringResource(R.string.viewer_info_modified_label),
                    value = formatDateTime(it, DateTimeStyle.FULL),
                    pairable = true,
                ),
            )
        }
        fileInfo.createdAt?.let {
            add(
                InfoEntry(
                    label = stringResource(R.string.viewer_info_created_label),
                    value = formatDateTime(it, DateTimeStyle.FULL),
                    pairable = true,
                ),
            )
        }
        fileInfo.imageInfo?.let { image ->
            add(
                InfoEntry(
                    label = stringResource(R.string.viewer_info_format_label),
                    value = image.format,
                    pairable = true,
                ),
            )
            if (image.width != null && image.height != null) {
                add(
                    InfoEntry(
                        label = stringResource(R.string.viewer_info_dimensions_label),
                        value = stringResource(
                            R.string.viewer_info_dimensions_value,
                            image.width,
                            image.height,
                        ),
                        pairable = true,
                    ),
                )
            }
        }
        // Two representations in one value (`rw-r--r-- (0644)`, `user / group`) - reads badly at half width.
        fileInfo.permissions?.let {
            add(
                InfoEntry(
                    label = stringResource(R.string.viewer_info_permissions_label),
                    value = stringResource(
                        R.string.viewer_info_permissions_value,
                        it.toReadableString(),
                        it.octal,
                    ),
                    pairable = false,
                ),
            )
        }
        fileInfo.ownership?.let {
            add(
                InfoEntry(
                    label = stringResource(R.string.viewer_info_owner_label),
                    value = stringResource(
                        R.string.viewer_info_owner_value,
                        it.userName ?: it.userId.toString(),
                        it.groupName ?: it.groupId.toString(),
                    ),
                    pairable = false,
                ),
            )
        }
    }

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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            groupInfoEntries(entries).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    row.forEach { InfoBlock(entry = it, modifier = Modifier.weight(1f)) }
                    // Keeps an odd trailing pairable entry at half width so the grid stays aligned.
                    if (row.size == 1 && row.first().pairable) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun InfoBlock(
    modifier: Modifier = Modifier,
    entry: InfoEntry,
) {
    Column(modifier = modifier) {
        Text(
            text = entry.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = entry.value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            // Plain ellipsis: MiddleEllipsis degrades to clipping on multiline Android text.
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
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

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FileInfoCardWrappingValuesPreview() {
    FileInfoCard(
        modifier = Modifier
            .width(360.dp)
            .padding(16.dp),
        fileInfo = ViewerFileInfo(
            size = 4_812_331_004L,
            modifiedAt = Clock.System.now() - 700.days,
            createdAt = Clock.System.now() - 900.days,
            permissions = Permissions(0b111_101_101),
            ownership = Ownership(userId = 1000L, groupId = 9997L, userName = "u0_a237", groupName = "everybody"),
            imageInfo = ViewerFileInfo.ImageInfo(format = "image/x-adobe-dng", width = 12000, height = 9000),
        ),
    )
}
