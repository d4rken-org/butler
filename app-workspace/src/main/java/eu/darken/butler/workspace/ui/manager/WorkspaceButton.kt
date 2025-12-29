package eu.darken.butler.workspace.ui.manager

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.visible
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material.icons.twotone.Workspaces
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.common.compose.ButlerMascot
import eu.darken.butler.common.compose.ButlerMascotMode
import eu.darken.butler.common.compose.LongClickableDropdownMenuItem
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction


@Composable
@SuppressLint("ModifierParameter")
fun WorkspaceButton(
    modifier: Modifier = Modifier,
    state: WorkspaceButtonViewModel.State?,
    containerColor: Color? = null,
    buttonSize: Dp = WORKSPACE_BUTTON_SIZE_DEFAULT,
    currentWorkspaceId: Workspace.Id? = null,
    workspaceActionHandler: WorkspaceActionHandler? = null,
    mascotVariant: ButlerMascotMode = ButlerMascotMode.Animated.RandomCycling(),
) {
    var expanded by remember { mutableStateOf(false) }
    var showCloseAllDialog by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        @Suppress("COMPOSE_APPLIER_CALL_MISMATCH")
        BoxWithConstraints(
            modifier = Modifier
                .size(buttonSize)
                .clip(RoundedCornerShape(8.dp))
                .background(containerColor ?: MaterialTheme.colorScheme.tertiaryContainer)
                .combinedClickable(
                    onClick = { expanded = true },
                    onLongClick = {
                        workspaceActionHandler?.navToWorkspaceManager()
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            val iconSize = minOf(maxWidth, maxHeight) * 0.8f

            ButlerMascot(
                modifier = Modifier.size(iconSize),
                variant = mascotVariant,
            )
        }

        // Dropdown menu
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.workspace_button_menu_manager_action)) },
                onClick = {
                    expanded = false
                    workspaceActionHandler?.navToWorkspaceManager()
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.TwoTone.Workspaces,
                        contentDescription = null
                    )
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.workspace_button_menu_settings_action)) },
                onClick = {
                    expanded = false
                    workspaceActionHandler?.navToSettings()
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.TwoTone.Settings,
                        contentDescription = null
                    )
                }
            )
            if (currentWorkspaceId != null) {
                LongClickableDropdownMenuItem(
                    text = stringResource(R.string.workspace_button_menu_close_current_action),
                    onClick = {
                        expanded = false
                        workspaceActionHandler?.executeWorkspaceAction(WorkspaceAction.Close(currentWorkspaceId))
                    },
                    onLongClick = {
                        expanded = false
                        showCloseAllDialog = true
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.TwoTone.Close,
                            contentDescription = null,
                        )
                    },
                )
            }
        }

        // Close all confirmation dialog
        CloseAllWorkspacesDialog(
            visible = showCloseAllDialog,
            workspaceCount = state?.workspaceCount ?: 0,
            onDismiss = { showCloseAllDialog = false },
            onConfirm = {
                showCloseAllDialog = false
                workspaceActionHandler?.executeWorkspaceAction(WorkspaceAction.CloseAll)
            }
        )

        // Badge showing workspace count (top-left)
        if (state?.workspaceCount != null && state.workspaceCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .run {
                        when {
                            buttonSize >= WORKSPACE_BUTTON_SIZE_DEFAULT -> this
                                .offset(x = (-6).dp, y = (-6).dp)
                                .size(14.dp)
                                .visible(true)
                            buttonSize >= WORKSPACE_BUTTON_SIZE_COMPACT -> this
                                .offset(x = (-4).dp, y = (-4).dp)
                                .size(12.dp)
                                .visible(true)
                            else -> this
                                .visible(false)
                        }
                    }
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (state.workspaceCount > 9) "9+" else state.workspaceCount.toString(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = if (buttonSize >= WORKSPACE_BUTTON_SIZE_DEFAULT) 9.sp else 7.sp,
                    lineHeight = if (buttonSize >= WORKSPACE_BUTTON_SIZE_DEFAULT) 9.sp else 7.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(bottom = if (buttonSize >= WORKSPACE_BUTTON_SIZE_DEFAULT) 1.dp else 0.dp)
                )
            }
        }

        // Badge showing operations count (top-right)
        if (state?.operationsCount != null && state.operationsCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .run {
                        when {
                            buttonSize >= WORKSPACE_BUTTON_SIZE_DEFAULT -> this
                                .offset(x = 6.dp, y = (-6).dp)
                                .size(14.dp)
                                .visible(true)
                            buttonSize >= WORKSPACE_BUTTON_SIZE_COMPACT -> this
                                .offset(x = 4.dp, y = (-4).dp)
                                .size(12.dp)
                                .visible(true)
                            else -> this
                                .visible(false)
                        }
                    }
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (state.operationsCount > 9) "9+" else state.operationsCount.toString(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = if (buttonSize >= WORKSPACE_BUTTON_SIZE_DEFAULT) 9.sp else 7.sp,
                    lineHeight = if (buttonSize >= WORKSPACE_BUTTON_SIZE_DEFAULT) 9.sp else 7.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(bottom = if (buttonSize >= WORKSPACE_BUTTON_SIZE_DEFAULT) 1.dp else 0.dp)
                )
            }
        }

        // Badge showing attention count (bottom-right)
        if (state?.attentionCount != null && state.attentionCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .run {
                        when {
                            buttonSize >= WORKSPACE_BUTTON_SIZE_DEFAULT -> this
                                .offset(x = 6.dp, y = 6.dp)
                                .size(14.dp)
                                .visible(true)
                            buttonSize >= WORKSPACE_BUTTON_SIZE_COMPACT -> this
                                .offset(x = 4.dp, y = 4.dp)
                                .size(12.dp)
                                .visible(true)
                            else -> this
                                .visible(false)
                        }
                    }
                    .background(
                        color = MaterialTheme.colorScheme.error,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (state.attentionCount > 9) "9+" else state.attentionCount.toString(),
                    color = MaterialTheme.colorScheme.onError,
                    fontSize = if (buttonSize >= WORKSPACE_BUTTON_SIZE_DEFAULT) 9.sp else 7.sp,
                    lineHeight = if (buttonSize >= WORKSPACE_BUTTON_SIZE_DEFAULT) 9.sp else 7.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(bottom = if (buttonSize >= WORKSPACE_BUTTON_SIZE_DEFAULT) 1.dp else 0.dp)
                )
            }
        }
    }
}

val WORKSPACE_BUTTON_SIZE_DEFAULT = 48.dp
val WORKSPACE_BUTTON_SIZE_COMPACT = 32.dp

@Preview2
@Composable
private fun WorkspaceButtonModifiedPreview() {
    PreviewWrapper {
        Box(
            modifier = Modifier
                .width(128.dp)
                .height(128.dp)
        ) {
            WorkspaceButton(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 16.dp),
                state = WorkspaceButtonViewModel.State(
                    workspaceCount = 5,
                    operationsCount = 7,
                    attentionCount = 1,
                ),
            )
        }
    }
}

@Preview2
@Composable
private fun WorkspaceButtonPreview() {
    PreviewWrapper {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            WorkspaceButton(
                buttonSize = 24.dp,
                state = WorkspaceButtonViewModel.State(
                    workspaceCount = 5,
                    operationsCount = 7,
                    attentionCount = 1,
                ),
            )

            WorkspaceButton(
                buttonSize = 32.dp,
                state = WorkspaceButtonViewModel.State(
                    workspaceCount = 5,
                    operationsCount = 7,
                    attentionCount = 1,
                ),
            )

            WorkspaceButton(
                state = WorkspaceButtonViewModel.State(
                    workspaceCount = 1,
                    operationsCount = 0,
                    attentionCount = 0,
                ),
            )

            WorkspaceButton(
                buttonSize = 72.dp,
                state = WorkspaceButtonViewModel.State(
                    workspaceCount = 3,
                    operationsCount = 2,
                    attentionCount = 0,
                ),
                currentWorkspaceId = Workspace.Id(),
            )

            WorkspaceButton(
                state = WorkspaceButtonViewModel.State(
                    workspaceCount = 5,
                    operationsCount = 7,
                    attentionCount = 1,
                ),
            )

            WorkspaceButton(
                state = WorkspaceButtonViewModel.State(
                    workspaceCount = 12,
                    operationsCount = 15,
                    attentionCount = 10,
                ),
            )
        }
    }
}