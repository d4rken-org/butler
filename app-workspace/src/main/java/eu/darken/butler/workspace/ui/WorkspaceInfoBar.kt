package eu.darken.butler.workspace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.CheckBox
import androidx.compose.material.icons.twotone.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.R
import eu.darken.butler.common.compose.ButlerChip
import eu.darken.butler.common.compose.ButlerChipDefaults
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.ui.pagerFriendlyHorizontalScroll

@Composable
fun WorkspaceInfoBar(
    modifier: Modifier = Modifier,
    selectedCount: Int = 0,
    onClearSelection: (() -> Unit)? = null,
    selectionText: @Composable (Int) -> String = {
        pluralStringResource(R.plurals.common_infobar_selected_count, selectedCount, selectedCount)
    },
    leadingContent: @Composable RowScope.() -> Unit = {},
    trailingContent: @Composable RowScope.() -> Unit = {},
) {
    val scrollState = rememberScrollState()
    val isWorkspaceFocused = LocalWorkspaceFocused.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pagerFriendlyHorizontalScroll(scrollState, isWorkspaceFocused = isWorkspaceFocused),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectedCount > 0) {
            InfoChip(
                icon = Icons.TwoTone.CheckBox,
                label = selectionText(selectedCount),
                isAccented = true,
                onClick = onClearSelection,
                trailingIcon = if (onClearSelection != null) Icons.TwoTone.Close else null,
            )
        }

        leadingContent()

        trailingContent()
    }
}

@Composable
fun InfoChip(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    isAccented: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailingIcon: ImageVector? = null,
) {
    ButlerChip(
        modifier = modifier,
        label = label,
        leadingIcon = icon,
        onClick = onClick,
        onRemove = if (trailingIcon != null) onClick else null,
        colors = if (isAccented) ButlerChipDefaults.highlightColors() else ButlerChipDefaults.colors(),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceInfoBarWithSelectionPreview() {
    WorkspaceInfoBar(
        selectedCount = 3,
        onClearSelection = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceInfoBarWithoutClearPreview() {
    WorkspaceInfoBar(
        selectedCount = 5,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceInfoBarWithContentPreview() {
    WorkspaceInfoBar(
        selectedCount = 0,
        leadingContent = {
            InfoChip(
                icon = Icons.TwoTone.CheckBox,
                label = "42 items"
            )
            InfoChip(
                icon = Icons.TwoTone.CheckBox,
                label = "7 folders"
            )
        },
        trailingContent = {
            Spacer(modifier = Modifier.weight(1f))
            InfoChip(
                icon = Icons.TwoTone.CheckBox,
                label = "512 MB"
            )
        }
    )
}
