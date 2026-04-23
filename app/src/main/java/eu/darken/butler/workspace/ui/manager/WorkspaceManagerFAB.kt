package eu.darken.butler.workspace.ui.manager

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.icon
import kotlinx.coroutines.launch

@Composable
fun WorkspaceManagerFAB(
    workspaceCount: Int,
    onCreateWorkspace: (Workspace.Type) -> Unit,
    onShowCloseAllDialog: () -> Unit,
    modifier: Modifier = Modifier,
    showLongPressHint: Boolean = true,
    onDismissLongPressHint: () -> Unit = {},
) {
    var showDropdown by remember { mutableStateOf(false) }
    var showLongPressHintThisSession by remember { mutableStateOf(showLongPressHint) }
    val hapticFeedback = LocalHapticFeedback.current
    val tooltipState = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()

    val orderedTypes = remember {
        listOf(
            Workspace.Type.EXPLORER,
            Workspace.Type.SEARCHER,
            Workspace.Type.EDITOR,
            Workspace.Type.APPS,
        )
    }

    Box(modifier = modifier) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(
                spacingBetweenTooltipAndAnchor = 16.dp
            ),
            tooltip = {
                PlainTooltip(
                    modifier = Modifier.widthIn(max = 280.dp)
                ) {
                    Text(stringResource(R.string.workspace_fab_longpress_hint))
                }
            },
            state = tooltipState,
            enableUserInput = false,
        ) {
            Surface(
                modifier = Modifier.combinedClickable(
                    onClick = {
                        if (showLongPressHint) {
                            if (showLongPressHintThisSession) {
                                scope.launch { tooltipState.show() }
                                showLongPressHintThisSession = false
                            } else {
                                onDismissLongPressHint()
                                scope.launch { tooltipState.dismiss() }
                            }
                        }
                        // Always execute action
                        onCreateWorkspace(Workspace.Type.TEMPLATES)
                    },
                    onLongClick = {
                        // Long press: dismiss tooltip if showing, show dropdown
                        if (showLongPressHint) {
                            onDismissLongPressHint()
                            scope.launch { tooltipState.dismiss() }
                            showLongPressHintThisSession = false
                        }

                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        showDropdown = true
                    }
                ),
                shape = FloatingActionButtonDefaults.extendedFabShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
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
        }

        DropdownMenu(
            expanded = showDropdown,
            onDismissRequest = { showDropdown = false },
            offset = DpOffset(x = 0.dp, y = (-8).dp)
        ) {
            // Type-safe dropdown: iterate through ordered workspace types
            orderedTypes.forEach { type ->
                DropdownMenuItem(
                    text = {
                        Text(
                            when (type) {
                                Workspace.Type.EXPLORER -> stringResource(R.string.workspace_fab_explorer)
                                Workspace.Type.SEARCHER -> stringResource(R.string.workspace_fab_search)
                                Workspace.Type.EDITOR -> stringResource(R.string.workspace_fab_editor)
                                Workspace.Type.APPS -> stringResource(R.string.workspace_fab_apps)
                                else -> throw UnsupportedOperationException("$type is not supported.")
                            }
                        )
                    },
                    onClick = {
                        onCreateWorkspace(type)
                        showDropdown = false
                    },
                    leadingIcon = {
                        Icon(type.icon, contentDescription = null)
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
        onCreateWorkspace = {},
        onShowCloseAllDialog = {},
        showLongPressHint = true,
        onDismissLongPressHint = {}
    )
}
