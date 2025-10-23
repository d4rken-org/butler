package eu.darken.butler.workspace.ui.manager

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.icon

@Composable
fun WorkspaceManagerFAB(
    workspaceCount: Int,
    onCreateWorkspace: (Workspace.Type) -> Unit,
    onShowCloseAllDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDropdown by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        ExtendedFloatingActionButton(
            onClick = { showDropdown = true },
            icon = {
                Icon(
                    imageVector = Icons.TwoTone.Add,
                    contentDescription = null
                )
            },
            text = { Text(stringResource(R.string.workspace_fab_add_workspace)) }
        )

        DropdownMenu(
            expanded = showDropdown,
            onDismissRequest = { showDropdown = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.workspace_fab_explorer)) },
                onClick = {
                    onCreateWorkspace(Workspace.Type.EXPLORER)
                    showDropdown = false
                },
                leadingIcon = {
                    Icon(Workspace.Type.EXPLORER.icon, contentDescription = null)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.workspace_fab_search)) },
                onClick = {
                    onCreateWorkspace(Workspace.Type.SEARCHER)
                    showDropdown = false
                },
                leadingIcon = {
                    Icon(Workspace.Type.SEARCHER.icon, contentDescription = null)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.workspace_fab_editor)) },
                onClick = {
                    onCreateWorkspace(Workspace.Type.EDITOR)
                    showDropdown = false
                },
                leadingIcon = {
                    Icon(Workspace.Type.EDITOR.icon, contentDescription = null)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.workspace_fab_templates)) },
                onClick = {
                    onCreateWorkspace(Workspace.Type.TEMPLATES)
                    showDropdown = false
                },
                leadingIcon = {
                    Icon(Workspace.Type.TEMPLATES.icon, contentDescription = null)
                }
            )
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
@Composable
private fun WorkspaceManagerFABPreview() {
    PreviewWrapper {
        WorkspaceManagerFAB(
            workspaceCount = 3,
            onCreateWorkspace = {},
            onShowCloseAllDialog = {}
        )
    }
}