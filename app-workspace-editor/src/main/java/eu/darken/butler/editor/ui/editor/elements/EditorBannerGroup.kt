package eu.darken.butler.editor.ui.editor.elements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
    backupNames: List<String> = emptyList(),
    showBackupNotice: Boolean = false,
    isBinary: Boolean = false,
    onDismissError: () -> Unit = {},
    onDismissBackupNotice: () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun EditorBannerGroupPreview() {
    EditorBannerGroup(
        error = RuntimeException("Failed to save file: Permission denied"),
        backupNames = listOf("notes.txt.butler-save-bak-1a2b3c4d"),
        showBackupNotice = true,
        isBinary = true,
    )
}
