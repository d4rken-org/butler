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

@Composable
fun WorkspaceLayoutDialog(
    modifier: Modifier = Modifier,
    visible: Boolean,
    currentMode: WorkspacePanelMode,
    geometries: List<WorkspacePanelMode>,
    isLandscape: Boolean = false,
    onDismiss: () -> Unit,
    onSelect: (WorkspacePanelMode) -> Unit,
) {
    if (!visible) return
    LayoutPickerDialog(
        modifier = modifier,
        title = stringResource(R.string.workspace_settings_layout_title),
        options = (listOf(WorkspacePanelMode.AUTO) + geometries).map { mode ->
            LayoutPickerOption(
                icon = mode.icon(landscape = isLandscape),
                label = mode.label(),
                description = mode.description(),
                selected = mode == currentMode,
                onSelect = { onSelect(mode) },
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
