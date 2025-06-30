package eu.darken.butler.workspace.ui.manager

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material.icons.twotone.Workspaces
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
import androidx.compose.ui.graphics.graphicsLayer
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace

@Composable
fun WorkspaceManagerFAB(
    workspaceCount: Int,
    fabOffsetY: Float,
    onCreateWorkspace: (Workspace.Type) -> Unit,
    onShowCloseAllDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDropdown by remember { mutableStateOf(false) }

    Box(
        modifier = modifier.graphicsLayer {
            translationY = fabOffsetY
        }
    ) {
        ExtendedFloatingActionButton(
            onClick = { showDropdown = true },
            icon = {
                Icon(
                    imageVector = Icons.TwoTone.Add,
                    contentDescription = null
                )
            },
            text = { Text("Add Workspace") }
        )

        DropdownMenu(
            expanded = showDropdown,
            onDismissRequest = { showDropdown = false }
        ) {
            DropdownMenuItem(
                text = { Text("Explorer") },
                onClick = {
                    onCreateWorkspace(Workspace.Type.EXPLORER)
                    showDropdown = false
                },
                leadingIcon = {
                    Icon(Icons.TwoTone.Folder, contentDescription = null)
                }
            )
            DropdownMenuItem(
                text = { Text("Search") },
                onClick = {
                    onCreateWorkspace(Workspace.Type.SEARCHER)
                    showDropdown = false
                },
                leadingIcon = {
                    Icon(Icons.TwoTone.Search, contentDescription = null)
                }
            )
            DropdownMenuItem(
                text = { Text("Editor") },
                onClick = {
                    onCreateWorkspace(Workspace.Type.EDITOR)
                    showDropdown = false
                },
                leadingIcon = {
                    Icon(Icons.TwoTone.Edit, contentDescription = null)
                }
            )
            DropdownMenuItem(
                text = { Text("Templates") },
                onClick = {
                    onCreateWorkspace(Workspace.Type.TEMPLATES)
                    showDropdown = false
                },
                leadingIcon = {
                    Icon(Icons.TwoTone.Workspaces, contentDescription = null)
                }
            )
            if (workspaceCount > 1) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Text(
                            "Close All Workspaces",
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
            fabOffsetY = 0f,
            onCreateWorkspace = {},
            onShowCloseAllDialog = {}
        )
    }
}