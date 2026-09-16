package eu.darken.butler.workspace.ui.manager

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import eu.darken.butler.workspace.ui.layout.ADAPTIVE_GEOMETRIES
import eu.darken.butler.workspace.ui.layout.LayoutPickerDialog
import eu.darken.butler.workspace.ui.layout.LayoutPickerOption
import eu.darken.butler.workspace.ui.layout.description
import eu.darken.butler.workspace.ui.layout.icon
import eu.darken.butler.workspace.ui.layout.label
import eu.darken.butler.workspace.ui.layout.requiresPro

/**
 * @param recommendedPaneCount what the window is recommended; geometries above it are Pro. Gated
 * rows stay listed - that is where the offer is - and select [onUpgrade] instead of [onSelect].
 */
@Composable
fun WorkspaceLayoutDialog(
    modifier: Modifier = Modifier,
    visible: Boolean,
    currentMode: WorkspacePanelMode,
    geometries: List<WorkspacePanelMode>,
    isLandscape: Boolean = false,
    isPro: Boolean = true,
    recommendedPaneCount: Int = 1,
    onDismiss: () -> Unit,
    onSelect: (WorkspacePanelMode) -> Unit,
    onUpgrade: () -> Unit = {},
) {
    if (!visible) return
    LayoutPickerDialog(
        modifier = modifier,
        title = stringResource(R.string.workspace_settings_layout_title),
        options = (listOf(WorkspacePanelMode.AUTO) + geometries).map { mode ->
            val gated = !isPro && mode.requiresPro(recommendedPaneCount)
            LayoutPickerOption(
                icon = mode.icon(landscape = isLandscape),
                label = mode.label(),
                description = mode.description(),
                selected = mode == currentMode,
                onSelect = { if (gated) onUpgrade() else onSelect(mode) },
                requiresUpgrade = gated,
            )
        },
        onDismiss = onDismiss,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceLayoutDialogPreview() {
    WorkspaceLayoutDialog(
        visible = true,
        currentMode = WorkspacePanelMode.DUAL_HORIZONTAL,
        geometries = ADAPTIVE_GEOMETRIES,
        onDismiss = {},
        onSelect = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceLayoutDialogGatedPreview() {
    WorkspaceLayoutDialog(
        visible = true,
        currentMode = WorkspacePanelMode.SINGLE_RAIL,
        geometries = ADAPTIVE_GEOMETRIES,
        isPro = false,
        recommendedPaneCount = 1,
        onDismiss = {},
        onSelect = {},
        onUpgrade = {},
    )
}
