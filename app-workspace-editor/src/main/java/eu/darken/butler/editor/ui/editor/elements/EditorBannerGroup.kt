package eu.darken.butler.editor.ui.editor.elements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

/**
 * Hosts the editor's notice banners as a single floating bar. One stable bar (instead of one per
 * banner) because [eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack] pairs bar state
 * with placeables by declaration index — conditionally registered bars would desynchronize.
 */
@Composable
fun EditorBannerGroup(
    modifier: Modifier = Modifier,
    error: Throwable? = null,
    showBackingLost: Boolean = false,
    showExternalChange: Boolean = false,
    backupNames: List<String> = emptyList(),
    showBackupNotice: Boolean = false,
    isBinary: Boolean = false,
    showLongLinesNotice: Boolean = false,
    onDismissError: () -> Unit = {},
    onCloseBackingLost: () -> Unit = {},
    onReloadFromDisk: () -> Unit = {},
    onDismissExternalChange: () -> Unit = {},
    onDismissBackupNotice: () -> Unit = {},
    onDismissLongLinesNotice: () -> Unit = {},
) {
    Column(
        // Scrollable so a height cap from the caller (short viewports) never hides a banner
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Backing-lost is terminal for editing; surface it above the transient notices
        if (showBackingLost) {
            EditorBackingLostBanner(
                onClose = onCloseBackingLost,
            )
        }

        if (showExternalChange) {
            EditorExternalChangeBanner(
                onReload = onReloadFromDisk,
                onKeepEditing = onDismissExternalChange,
            )
        }

        error?.let {
            EditorErrorBanner(
                error = it,
                onDismiss = onDismissError,
            )
        }

        if (showBackupNotice) {
            EditorBackupBanner(
                backupNames = backupNames,
                onDismiss = onDismissBackupNotice,
            )
        }

        if (isBinary) {
            EditorBinaryBanner()
        }

        if (showLongLinesNotice) {
            EditorLongLinesBanner(
                onDismiss = onDismissLongLinesNotice,
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun EditorBannerGroupPreview() {
    EditorBannerGroup(
        error = RuntimeException("Failed to save file: Permission denied"),
        showBackingLost = true,
        showExternalChange = true,
        backupNames = listOf("notes.txt.butler-save-bak-1a2b3c4d"),
        showBackupNotice = true,
        isBinary = true,
        showLongLinesNotice = true,
    )
}
