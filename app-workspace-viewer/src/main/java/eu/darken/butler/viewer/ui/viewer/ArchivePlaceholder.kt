package eu.darken.butler.viewer.ui.viewer

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.FolderZip
import androidx.compose.material.icons.twotone.SaveAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.files.archive.ArchiveFormat
import eu.darken.butler.viewer.R
import eu.darken.butler.viewer.core.ViewerContent
import java.util.Locale

/**
 * What the viewer shows for a container: there is nothing to render, so it says what this file is
 * and how to get into it.
 *
 * The action follows [access] rather than being derived from the source here - the two cases that
 * cannot be browsed need different answers, and only one of them has an action at all. A nested
 * archive gets no button on purpose: saving a copy cannot serve it either.
 */
@Composable
fun ArchivePlaceholder(
    modifier: Modifier = Modifier,
    format: ArchiveFormat,
    access: ViewerContent.Archive.Access,
    onBrowse: () -> Unit = {},
    onSaveCopy: () -> Unit = {},
    onToggleChrome: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .let { base ->
                if (onToggleChrome == null) base
                else base.pointerInput(onToggleChrome) { detectTapGestures { onToggleChrome() } }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.TwoTone.FolderZip,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = stringResource(R.string.viewer_archive_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = format.displayExtension.uppercase(Locale.ROOT),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            when (access) {
                ViewerContent.Archive.Access.BROWSABLE -> Button(onClick = onBrowse) {
                    Icon(
                        imageVector = Icons.TwoTone.FolderZip,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.viewer_browse_archive_action),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                ViewerContent.Archive.Access.NEEDS_COPY -> {
                    Text(
                        text = stringResource(R.string.viewer_archive_needs_copy_msg),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onSaveCopy) {
                        Icon(
                            imageVector = Icons.TwoTone.SaveAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.viewer_save_copy_action),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }

                ViewerContent.Archive.Access.NESTED -> Text(
                    text = stringResource(R.string.viewer_archive_nested_msg),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ArchivePlaceholderBrowsablePreview() {
    ArchivePlaceholder(
        format = ArchiveFormat.ZIP,
        access = ViewerContent.Archive.Access.BROWSABLE,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ArchivePlaceholderNeedsCopyPreview() {
    ArchivePlaceholder(
        format = ArchiveFormat.TAR_GZ,
        access = ViewerContent.Archive.Access.NEEDS_COPY,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ArchivePlaceholderNestedPreview() {
    ArchivePlaceholder(
        format = ArchiveFormat.ZIP,
        access = ViewerContent.Archive.Access.NESTED,
    )
}
