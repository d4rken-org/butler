package eu.darken.butler.main.ui.external

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.InsertDriveFile
import androidx.compose.material.icons.twotone.Android
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.material.icons.twotone.FolderOpen
import androidx.compose.material.icons.twotone.Image
import androidx.compose.material.icons.twotone.PictureAsPdf
import androidx.compose.material.icons.twotone.SaveAlt
import androidx.compose.material.icons.twotone.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.SubcomposeAsyncImage
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
 *
 * The choices are a stack of separate surfaces rather than a single dialog body, so each one reads
 * as its own target and the scrim stays visible between them.
 */
@Composable
fun ExternalOpenDialog(
    modifier: Modifier = Modifier,
    displayName: String,
    mime: MimeInfo,
    sizeBytes: Long?,
    previewUri: Uri?,
    options: List<ExternalOpenOption>,
    onOption: (ExternalOpenOption) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        // The dialog window doesn't dim by default here, and the stack leaves gaps between its cards,
        // so without a scrim of our own the content behind shows through them. Painting it ourselves
        // (like the workspace modals do) also lets it run under the system bars.
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = modifier
                    .safeDrawingPadding()
                    // A short screen (landscape, split screen, large font scale) can be smaller than
                    // the stack, and a clipped Cancel row would be unreachable.
                    .verticalScroll(rememberScrollState())
                    .widthIn(max = DIALOG_MAX_WIDTH)
                    .padding(horizontal = DIALOG_EDGE_PADDING)
                    // The stack itself is not a dismiss target, only the scrim around it.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                verticalArrangement = Arrangement.spacedBy(CANCEL_GAP),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(STACK_GAP)) {
                    ExternalOpenHeaderCard(
                        displayName = displayName,
                        mime = mime,
                        sizeBytes = sizeBytes,
                        previewUri = previewUri,
                        shape = RoundedCornerShape(
                            topStart = OUTER_CORNER,
                            topEnd = OUTER_CORNER,
                            bottomStart = INNER_CORNER,
                            bottomEnd = INNER_CORNER,
                        ),
                    )
                    options.forEach { option ->
                        ExternalOpenOptionRow(
                            option = option,
                            shape = RoundedCornerShape(INNER_CORNER),
                            onClick = { onOption(option) },
                        )
                    }
                }
                ExternalOpenCancelRow(
                    shape = RoundedCornerShape(
                        topStart = INNER_CORNER,
                        topEnd = INNER_CORNER,
                        bottomStart = OUTER_CORNER,
                        bottomEnd = OUTER_CORNER,
                    ),
                    onClick = onDismiss,
                )
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ExternalOpenDialogImagePreview() {
    ExternalOpenDialog(
        displayName = "IMG_20260817_120000.jpg",
        mime = MimeInfo("image/jpeg"),
        sizeBytes = 2_400_000L,
        previewUri = null,
        options = listOf(ExternalOpenOption.VIEW, ExternalOpenOption.SAVE_AS),
        onOption = {},
        onDismiss = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ExternalOpenDialogApkPreview() {
    ExternalOpenDialog(
        displayName = "SD_Maid-v5.6.3.50603.-RELEASE.apk",
        mime = MimeInfo("application/vnd.android.package-archive"),
        sizeBytes = 8_700_000L,
        previewUri = null,
        options = listOf(
            ExternalOpenOption.VIEW,
            ExternalOpenOption.SHOW_IN_EXPLORER,
            ExternalOpenOption.SAVE_AS,
        ),
        onOption = {},
        onDismiss = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ExternalOpenDialogPdfPreview() {
    ExternalOpenDialog(
        displayName = "invoice-2026-08.pdf",
        mime = MimeInfo("application/pdf"),
        sizeBytes = 180_000L,
        previewUri = null,
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
        previewUri = null,
        options = listOf(ExternalOpenOption.EDIT_AS_TEXT, ExternalOpenOption.SAVE_AS),
        onOption = {},
        onDismiss = {},
    )
}

@Composable
private fun ExternalOpenHeaderCard(
    modifier: Modifier = Modifier,
    displayName: String,
    mime: MimeInfo,
    sizeBytes: Long?,
    previewUri: Uri?,
    shape: Shape,
) {
    val typeName = mime.toUserFriendlyName(LocalContext.current)
    val subtitle = when (sizeBytes) {
        null -> typeName
        else -> "$typeName · ${formatFileSize(sizeBytes)}"
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (previewUri) {
                null -> ExternalOpenTypeIcon(mime = mime)
                else -> SubcomposeAsyncImage(
                    model = previewUri,
                    contentDescription = null,
                    modifier = Modifier
                        .size(LEADING_SIZE)
                        .clip(RoundedCornerShape(LEADING_CORNER)),
                    contentScale = ContentScale.Crop,
                    loading = { ExternalOpenTypeIcon(mime = mime) },
                    error = { ExternalOpenTypeIcon(mime = mime) },
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = displayName.ifBlank { stringResource(R.string.external_open_unknown_file_title) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ExternalOpenTypeIcon(
    modifier: Modifier = Modifier,
    mime: MimeInfo,
) {
    Box(
        modifier = modifier
            .size(LEADING_SIZE)
            .clip(RoundedCornerShape(LEADING_CORNER))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = mime.typeIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun ExternalOpenOptionRow(
    modifier: Modifier = Modifier,
    option: ExternalOpenOption,
    shape: Shape = RoundedCornerShape(INNER_CORNER),
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp),
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
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ExternalOpenOptionRowPreview() {
    ExternalOpenOptionRow(
        option = ExternalOpenOption.EDIT_AS_TEXT,
        onClick = {},
    )
}

@Composable
private fun ExternalOpenCancelRow(
    modifier: Modifier = Modifier,
    shape: Shape,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(CommonR.string.general_cancel_action),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private val MimeInfo.typeIcon: ImageVector
    get() = when {
        isImage -> Icons.TwoTone.Image
        isPdf -> Icons.TwoTone.PictureAsPdf
        isApk -> Icons.TwoTone.Android
        isText -> Icons.TwoTone.Description
        else -> Icons.AutoMirrored.TwoTone.InsertDriveFile
    }

private val ExternalOpenOption.icon: ImageVector
    get() = when (this) {
        ExternalOpenOption.VIEW -> Icons.TwoTone.Visibility
        ExternalOpenOption.EDIT_AS_TEXT -> Icons.TwoTone.Edit
        ExternalOpenOption.SHOW_IN_EXPLORER -> Icons.TwoTone.FolderOpen
        ExternalOpenOption.SAVE_AS -> Icons.TwoTone.SaveAlt
    }

private val ExternalOpenOption.labelRes: Int
    get() = when (this) {
        ExternalOpenOption.VIEW -> R.string.external_open_action_view
        ExternalOpenOption.EDIT_AS_TEXT -> R.string.external_open_action_edit
        ExternalOpenOption.SHOW_IN_EXPLORER -> R.string.external_open_action_show_in_explorer
        ExternalOpenOption.SAVE_AS -> R.string.external_open_action_save
    }

private val OUTER_CORNER = 24.dp
private val INNER_CORNER = 8.dp
private val STACK_GAP = 3.dp
private val CANCEL_GAP = 8.dp
private val DIALOG_MAX_WIDTH = 380.dp
private val DIALOG_EDGE_PADDING = 24.dp
private const val SCRIM_ALPHA = 0.5f
private val LEADING_SIZE = 48.dp
private val LEADING_CORNER = 12.dp
