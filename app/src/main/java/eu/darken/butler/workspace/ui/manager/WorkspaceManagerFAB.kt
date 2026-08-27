package eu.darken.butler.workspace.ui.manager

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.compose.tour.guidedTourTarget
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.tour.WorkspaceManagerTour
import eu.darken.butler.workspace.ui.template.QuickCreateItem

@Composable
fun WorkspaceManagerFAB(
    workspaceCount: Int,
    quickCreateItems: List<QuickCreateItem>,
    onCreateWorkspace: (Workspace.Type) -> Unit,
    onQuickCreate: (QuickCreateItem) -> Unit,
    onShowCloseAllDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDropdown by remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current

    Box(modifier = modifier) {
        Surface(
            modifier = Modifier.guidedTourTarget(WorkspaceManagerTour.ADD_TAB_TARGET),
            shape = FloatingActionButtonDefaults.extendedFabShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier
                    .combinedClickable(
                        onClick = { onCreateWorkspace(Workspace.Type.TEMPLATES) },
                        onLongClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            showDropdown = true
                        }
                    )
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.TwoTone.Add,
                    contentDescription = null
                )
                Text(stringResource(R.string.workspace_fab_add_workspace))
            }
        }

        DropdownMenu(
            expanded = showDropdown,
            onDismissRequest = { showDropdown = false },
            offset = DpOffset(x = 0.dp, y = (-8).dp)
        ) {
            quickCreateItems.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.title.asComposable()) },
                    onClick = {
                        onQuickCreate(item)
                        showDropdown = false
                    },
                    leadingIcon = {
                        Icon(item.icon, contentDescription = null)
                    }
                )
            }

            if (workspaceCount > 1) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.workspace_fab_close_all),
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        showDropdown = false
                        onShowCloseAllDialog()
                    },
                    leadingIcon = {
                        Icon(
                            Icons.TwoTone.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                )
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceManagerFABPreview() {
    WorkspaceManagerFAB(
        workspaceCount = 3,
        quickCreateItems = emptyList(),
        onCreateWorkspace = {},
        onQuickCreate = {},
        onShowCloseAllDialog = {},
    )
}
