package eu.darken.butler.workspace.ui.manager

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material.icons.twotone.Workspaces
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.LongClickableDropdownMenuItem
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.defaultArguments
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.ui.modal.DismissWhenPaneUnfocused
import eu.darken.butler.workspace.ui.template.QuickCreateItem

@Composable
fun WorkspaceButtonMenu(
    modifier: Modifier = Modifier,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    state: WorkspaceButtonViewModel.State?,
    currentWorkspaceId: Workspace.Id? = null,
    provider: WorkspaceButtonProvider?,
    onCloseAllRequested: () -> Unit,
    onOpenManager: () -> Unit,
) {
    DismissWhenPaneUnfocused(expanded = expanded, onDismiss = onDismissRequest)
    DropdownMenu(
        modifier = modifier,
        expanded = expanded,
        onDismissRequest = onDismissRequest,
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.workspace_button_menu_new_tab_action)) },
            onClick = {
                onDismissRequest()
                provider?.createTemplatesWorkspace()
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.TwoTone.Add,
                    contentDescription = null,
                )
            },
            colors = MenuDefaults.itemColors(
                textColor = NewTabColor,
                leadingIconColor = NewTabColor,
            ),
        )

        val recentItems = state?.recentItems ?: emptyList()
        if (recentItems.isNotEmpty()) {
            MenuCategoryHeader(text = stringResource(R.string.workspace_button_menu_category_recent))
            recentItems.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                R.string.workspace_button_menu_new_workspace_format,
                                item.title.asComposable(),
                            )
                        )
                    },
                    onClick = {
                        onDismissRequest()
                        provider?.createWorkspace(item)
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                        )
                    },
                )
            }
        }

        MenuCategoryHeader(text = stringResource(R.string.workspace_button_menu_category_other))
        DropdownMenuItem(
            text = { Text(stringResource(R.string.workspace_button_menu_manager_action)) },
            onClick = {
                onDismissRequest()
                onOpenManager()
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.TwoTone.Workspaces,
                    contentDescription = null,
                )
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.workspace_button_menu_settings_action)) },
            onClick = {
                onDismissRequest()
                provider?.navToSettings()
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.TwoTone.Settings,
                    contentDescription = null,
                )
            },
        )

        if (currentWorkspaceId != null) {
            // Closes the whole ownership unit, not just the workspace the button sits in: the user
            // sees a tab and the overlays stacked on it as one thing, so closing only the top
            // overlay would leave behind the very tab this row names. Same target as the manager's
            // card, which has always closed the unit.
            val unit = state?.unitsByMember?.get(currentWorkspaceId)
            val closeTargetId = unit?.ownerId ?: currentWorkspaceId
            MenuCategoryHeader(text = stringResource(R.string.workspace_button_menu_category_current_tab))
            LongClickableDropdownMenuItem(
                text = if (unit != null && unit.size > 1) {
                    pluralStringResource(
                        R.plurals.workspace_button_menu_close_current_stack_action,
                        unit.size,
                        unit.size,
                    )
                } else {
                    stringResource(R.string.workspace_button_menu_close_current_action)
                },
                onClick = {
                    onDismissRequest()
                    provider?.executeWorkspaceAction(
                        WorkspaceAction.Close(
                            id = closeTargetId,
                            sourceWorkspaceId = currentWorkspaceId,
                            undoable = true,
                        )
                    )
                },
                onLongClick = {
                    onDismissRequest()
                    onCloseAllRequested()
                },
                contentColor = MaterialTheme.colorScheme.error,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.TwoTone.Close,
                        contentDescription = null,
                    )
                },
            )
        }
    }
}

private val NewTabColor = Color(0xFF4CAF50)

@Composable
fun MenuCategoryHeader(
    modifier: Modifier = Modifier,
    text: String,
) {
    Text(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { heading() },
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun MenuCategoryHeaderPreview() {
    MenuCategoryHeader(text = "Recently")
}

private fun previewItem(type: Workspace.Type, title: String) = QuickCreateItem(
    type = type,
    icon = type.icon,
    title = title.toCaString(),
    arguments = type.defaultArguments!!,
)

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceButtonMenuPreview() {
    WorkspaceButtonMenu(
        expanded = true,
        onDismissRequest = {},
        state = WorkspaceButtonViewModel.State(
            workspaceCount = 3,
            recentItems = listOf(
                previewItem(Workspace.Type.EXPLORER, "Explorer"),
                previewItem(Workspace.Type.SEARCHER, "Search"),
                previewItem(Workspace.Type.EDITOR, "Editor"),
            ),
        ),
        currentWorkspaceId = Workspace.Id(),
        provider = FakeWorkspaceButtonProvider(),
        onCloseAllRequested = {},
        onOpenManager = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceButtonMenuFreshInstallPreview() {
    WorkspaceButtonMenu(
        expanded = true,
        onDismissRequest = {},
        state = WorkspaceButtonViewModel.State(
            recentItems = listOf(
                previewItem(Workspace.Type.EXPLORER, "Explorer"),
                previewItem(Workspace.Type.SEARCHER, "Search"),
                previewItem(Workspace.Type.EDITOR, "Editor"),
            ),
        ),
        currentWorkspaceId = null,
        provider = FakeWorkspaceButtonProvider(),
        onCloseAllRequested = {},
        onOpenManager = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceButtonMenuNoRecentsPreview() {
    WorkspaceButtonMenu(
        expanded = true,
        onDismissRequest = {},
        state = WorkspaceButtonViewModel.State(workspaceCount = 1),
        currentWorkspaceId = Workspace.Id(),
        provider = FakeWorkspaceButtonProvider(),
        onCloseAllRequested = {},
        onOpenManager = {},
    )
}
