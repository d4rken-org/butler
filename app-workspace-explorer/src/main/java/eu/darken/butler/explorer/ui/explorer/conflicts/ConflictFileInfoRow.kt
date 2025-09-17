package eu.darken.butler.explorer.ui.explorer.conflicts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ByteFormatter.formatFileSize
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.FileType
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.explorer.R
import java.text.DateFormat
import java.util.Date
import kotlin.time.Instant

@Composable
fun PathConflictFileComparisonCard(
    lookup: APathLookup<APath>,
    modifier: Modifier = Modifier,
) {
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = when (lookup.fileType) {
                    FileType.DIRECTORY -> Icons.TwoTone.Folder
                    else -> Icons.TwoTone.Description
                },
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = lookup.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            Text(
                text = when (lookup.fileType) {
                    FileType.FILE -> formatFileSize(lookup.size)
                    FileType.DIRECTORY -> stringResource(R.string.explorer_type_folder)
                    else -> "-"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = dateFormat.format(Date(lookup.modifiedAt.toEpochMilliseconds())),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview2
@Composable
private fun PathConflictFileComparisonCardFilePreview() {
    PreviewWrapper {
        PathConflictFileComparisonCard(
            lookup = LocalPathLookup(
                lookedUp = LocalPath.build("/storage/emulated/0/Download/very-long-documentname-that-ellipsizes.pdf"),
                fileType = FileType.FILE,
                size = 1024 * 1024 * 5, // 5MB
                modifiedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() - 86400000), // 1 day ago
                target = null,
            ),
        )
    }
}

@Preview2
@Composable
private fun PathConflictFileComparisonCardFolderPreview() {
    PreviewWrapper {
        PathConflictFileComparisonCard(
            lookup = LocalPathLookup(
                lookedUp = LocalPath.build("/storage/emulated/0/Pictures/Vacation"),
                fileType = FileType.DIRECTORY,
                size = 0,
                modifiedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() - 3600000), // 1 hour ago
                target = null,
            ),
        )
    }
}