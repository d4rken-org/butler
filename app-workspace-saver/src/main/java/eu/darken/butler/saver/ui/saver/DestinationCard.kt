package eu.darken.butler.saver.ui.saver

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.saver.R

@Composable
internal fun DestinationCard(
    modifier: Modifier = Modifier,
    destination: APath<*>?,
    filename: String?,
    isBatchMode: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.TwoTone.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.saver_destination_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    destination == null -> {
                        // Prompt to select destination
                        Text(
                            text = stringResource(R.string.saver_select_destination_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    isBatchMode -> {
                        // Batch mode: show only directory
                        Text(
                            text = destination.userReadablePath.get(context),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    !filename.isNullOrBlank() -> {
                        // Single file mode with filename: show full path
                        Text(
                            text = "${destination.userReadablePath.get(context)}/$filename",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    else -> {
                        // Single file mode without filename: show directory only
                        Text(
                            text = destination.userReadablePath.get(context),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Preview2
@Composable
private fun DestinationCardEmptyPreview() {
    PreviewWrapper {
        DestinationCard(
            destination = null,
            filename = "",
            isBatchMode = false,
            onClick = {},
        )
    }
}

@Preview2
@Composable
private fun DestinationCardSingleFilePreview() {
    PreviewWrapper {
        DestinationCard(
            destination = LocalPath.build("/storage/emulated/0/Download"),
            filename = "vacation_photo.jpg",
            isBatchMode = false,
            onClick = {},
        )
    }
}

@Preview2
@Composable
private fun DestinationCardBatchModePreview() {
    PreviewWrapper {
        DestinationCard(
            destination = LocalPath.build("/storage/emulated/0/Download"),
            filename = null,
            isBatchMode = true,
            onClick = {},
        )
    }
}
