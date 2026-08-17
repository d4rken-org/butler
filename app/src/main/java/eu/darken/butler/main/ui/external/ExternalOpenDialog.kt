package eu.darken.butler.main.ui.external

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.material.icons.twotone.SaveAlt
import androidx.compose.material.icons.twotone.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.toUserFriendlyName
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.main.core.external.ExternalOpenOption
import eu.darken.butler.common.R as CommonR

/**
 * Asks what should happen with a file another app handed to Butler via "Open with".
 */
@Composable
fun ExternalOpenDialog(
    modifier: Modifier = Modifier,
    displayName: String,
    mime: MimeInfo,
    sizeBytes: Long?,
    options: List<ExternalOpenOption>,
    onOption: (ExternalOpenOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val typeName = mime.toUserFriendlyName(LocalContext.current)
    val subtitle = when (sizeBytes) {
        null -> typeName
        else -> "$typeName · ${formatFileSize(sizeBytes)}"
    }

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = displayName.ifBlank { stringResource(R.string.external_open_unknown_file_title) },
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                options.forEach { option ->
                    ExternalOpenOptionRow(
                        option = option,
                        onClick = { onOption(option) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CommonR.string.general_cancel_action))
            }
        },
    )
}

@Composable
private fun ExternalOpenOptionRow(
    modifier: Modifier = Modifier,
    option: ExternalOpenOption,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = option.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(option.labelRes),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

private val ExternalOpenOption.icon: ImageVector
    get() = when (this) {
        ExternalOpenOption.VIEW -> Icons.TwoTone.Visibility
        ExternalOpenOption.EDIT_AS_TEXT -> Icons.TwoTone.Edit
        ExternalOpenOption.SAVE_AS -> Icons.TwoTone.SaveAlt
    }

private val ExternalOpenOption.labelRes: Int
    get() = when (this) {
        ExternalOpenOption.VIEW -> R.string.external_open_action_view
        ExternalOpenOption.EDIT_AS_TEXT -> R.string.external_open_action_edit
        ExternalOpenOption.SAVE_AS -> R.string.external_open_action_save
    }

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ExternalOpenDialogImagePreview() {
    ExternalOpenDialog(
        displayName = "IMG_20260817_120000.jpg",
        mime = MimeInfo("image/jpeg"),
        sizeBytes = 2_400_000L,
        options = listOf(ExternalOpenOption.VIEW, ExternalOpenOption.SAVE_AS),
        onOption = {},
        onDismiss = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ExternalOpenDialogTextPreview() {
    ExternalOpenDialog(
        displayName = "notes.txt",
        mime = MimeInfo("text/plain"),
        sizeBytes = null,
        options = listOf(ExternalOpenOption.EDIT_AS_TEXT, ExternalOpenOption.SAVE_AS),
        onOption = {},
        onDismiss = {},
    )
}
