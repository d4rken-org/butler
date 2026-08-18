package eu.darken.butler.viewer.ui.viewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.viewer.R
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialog
import eu.darken.butler.common.R as CommonR

/**
 * The archive's launcher icon at export resolution, with the pixel size it actually came out at -
 * an app that only ships a small icon shows a small number here, which is the honest answer.
 *
 * Deliberately not zoomable: the render is capped at 512px and the dialog shows it near or above
 * 1:1 already, so a gesture surface would add a control with nothing behind it.
 */
private val ICON_DISPLAY_SIZE = 176.dp

@Composable
fun ApkIconPreviewDialog(
    modifier: Modifier = Modifier,
    state: ViewerWorkspaceViewModel.IconPreviewState,
    appLabel: String,
    onDismiss: () -> Unit,
    onSave: () -> Unit = {},
) {
    PaneBoundAlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(text = appLabel) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // A fixed square rather than a full-width one: the icon is a small square asset, so
                // sizing the slot to the dialog's width left most of it empty on device.
                Box(
                    modifier = Modifier.size(ICON_DISPLAY_SIZE),
                    contentAlignment = Alignment.Center,
                ) {
                    when (state) {
                        ViewerWorkspaceViewModel.IconPreviewState.Loading -> CircularProgressIndicator()

                        is ViewerWorkspaceViewModel.IconPreviewState.Ready -> Image(
                            modifier = Modifier.fillMaxSize(),
                            bitmap = state.bitmap.asImageBitmap(),
                            contentDescription = stringResource(
                                R.string.viewer_apk_icon_content_description,
                                appLabel,
                            ),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
                if (state is ViewerWorkspaceViewModel.IconPreviewState.Ready) {
                    Text(
                        text = stringResource(
                            R.string.viewer_apk_icon_dimensions,
                            state.bitmap.width,
                            state.bitmap.height,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                enabled = state is ViewerWorkspaceViewModel.IconPreviewState.Ready,
            ) {
                Text(text = stringResource(R.string.viewer_apk_icon_save_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(CommonR.string.general_close_action))
            }
        },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ApkIconPreviewDialogReadyPreview() {
    ApkIconPreviewDialog(
        state = ViewerWorkspaceViewModel.IconPreviewState.Ready(
            createBitmap(192, 192).apply { eraseColor(0xFF3DDC84.toInt()) },
        ),
        appLabel = "Butler",
        onDismiss = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ApkIconPreviewDialogLoadingPreview() {
    ApkIconPreviewDialog(
        state = ViewerWorkspaceViewModel.IconPreviewState.Loading,
        appLabel = "Butler",
        onDismiss = {},
    )
}
