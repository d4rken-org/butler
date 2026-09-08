package eu.darken.butler.workspace.ui.layout

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2

data class LayoutPickerOption(
    val icon: ImageVector,
    val label: String,
    val description: String,
    val selected: Boolean,
    val onSelect: () -> Unit,
)

@Composable
fun LayoutPickerDialog(
    modifier: Modifier = Modifier,
    title: String,
    options: List<LayoutPickerOption>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = option.selected,
                                onClick = option.onSelect,
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = option.icon,
                            contentDescription = null,
                            tint = if (option.selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            },
                            modifier = Modifier.size(32.dp),
                        )
                        Column(modifier = Modifier.padding(start = 16.dp)) {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = option.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun LayoutPickerDialogPreview() {
    LayoutPickerDialog(
        title = "Layout",
        options = listOf(
            LayoutPickerOption(
                icon = WorkspacePanelIcons.Auto,
                label = "Automatic",
                description = "Classic on small screens, tab rail with panes on large ones.",
                selected = true,
                onSelect = {},
            ),
            LayoutPickerOption(
                icon = WorkspacePanelIcons.Single,
                label = "Classic",
                description = "One pane at a time, without the tab rail.",
                selected = false,
                onSelect = {},
            ),
            LayoutPickerOption(
                icon = WorkspacePanelIcons.Adaptive,
                label = "Adaptive",
                description = "Tab rail plus as many panes as the screen fits.",
                selected = false,
                onSelect = {},
            ),
        ),
        onDismiss = {},
    )
}
