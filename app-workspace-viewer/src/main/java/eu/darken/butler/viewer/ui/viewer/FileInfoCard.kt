package eu.darken.butler.viewer.ui.viewer

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ExpandLess
import androidx.compose.material.icons.twotone.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
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
 * Collapsed keeps the first row only. That row is Size and Modified for every file the gateway
 * reports anything about, which is the pair worth seeing without expanding.
 */
internal fun visibleInfoRows(rows: List<List<InfoEntry>>, expanded: Boolean): List<List<InfoEntry>> =
    if (expanded) rows else rows.take(1)

/**
 * Permissions carries two representations in one value (`rw-rw---- (100660)`, 18 characters at
 * bodySmall). That fits a half-width column on a full-width card, but not in a pane: the user can
 * force DUAL_VERTICAL in portrait, which halves a 360dp phone into 180dp panes and leaves each
 * column near 60dp, where the two-line cap would ellipsize the octal away.
 */
internal fun permissionsFitHalfWidth(cardWidth: Dp): Boolean = cardWidth >= PairedPermissionsMinCardWidth

internal val PairedPermissionsMinCardWidth = 280.dp

/**
 * Bottom floating card with the file's metadata. Rows for values the gateway did not provide are
 * omitted entirely - permissions and ownership are meaningless on public/SAF storage and showing
 * an empty row there would be worse than showing nothing.
 */
@Composable
fun FileInfoCard(
    modifier: Modifier = Modifier,
    fileInfo: ViewerFileInfo,
    initiallyExpanded: Boolean = true,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        FileInfoCardContent(
            fileInfo = fileInfo,
            initiallyExpanded = initiallyExpanded,
            pairPermissions = permissionsFitHalfWidth(maxWidth),
        )
    }
}

@Composable
private fun FileInfoCardContent(
    fileInfo: ViewerFileInfo,
    initiallyExpanded: Boolean,
    pairPermissions: Boolean,
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
                    value = formatDateTime(it, DateTimeStyle.COMPACT),
                    pairable = true,
                ),
            )
        }
        // Android hands out the same value for both on most storage, and a row that only repeats
        // the one above it is noise. A file where they genuinely differ still shows both.
        fileInfo.createdAt?.takeIf { it != fileInfo.modifiedAt }?.let {
            add(
                InfoEntry(
                    label = stringResource(R.string.viewer_info_created_label),
                    value = formatDateTime(it, DateTimeStyle.COMPACT),
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
        // Pairs only where the column is wide enough for the whole value, see
        // [permissionsFitHalfWidth]. Ownership never pairs - `userName / groupName` is a full
        // package name on media storage.
        fileInfo.permissions?.let {
            add(
                InfoEntry(
                    label = stringResource(R.string.viewer_info_permissions_label),
                    value = stringResource(
                        R.string.viewer_info_permissions_value,
                        it.toReadableString(),
                        it.octal,
                    ),
                    pairable = pairPermissions,
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

    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val rows = groupInfoEntries(entries)
    val canCollapse = rows.size > 1

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                visibleInfoRows(rows, expanded).forEachIndexed { index, row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // The toggle floats over the card's top end corner, so only the row it
                            // would overlap gets out of its way.
                            .padding(end = if (index == 0 && canCollapse) ToggleReservedWidth else 0.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        row.forEach { InfoBlock(entry = it, modifier = Modifier.weight(1f)) }
                        // Keeps an odd trailing pairable entry at half width so the grid stays aligned.
                        if (row.size == 1 && row.first().pairable) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            if (canCollapse) {
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(ToggleReservedWidth),
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.TwoTone.ExpandLess else Icons.TwoTone.ExpandMore,
                        contentDescription = stringResource(
                            if (expanded) R.string.viewer_info_collapse_action
                            else R.string.viewer_info_expand_action,
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Footprint of the expand/collapse button, and the end inset the first row reserves for it. */
private val ToggleReservedWidth = 40.dp

@Composable
internal fun InfoBlock(
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
private fun FileInfoCardCollapsedPreview() {
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
        initiallyExpanded = false,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FileInfoCardSameCreatedAndModifiedPreview() {
    val stamp = Clock.System.now() - 2.days
    FileInfoCard(
        modifier = Modifier
            .width(360.dp)
            .padding(16.dp),
        fileInfo = ViewerFileInfo(
            size = 233_472L,
            modifiedAt = stamp,
            createdAt = stamp,
            permissions = Permissions(0b110_110_000),
            ownership = Ownership(
                userId = 1023L,
                groupId = 1023L,
                userName = "com.google.android.providers.media.module",
                groupName = "media_rw",
            ),
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
