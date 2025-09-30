package eu.darken.butler.explorer.ui.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.ui.explorer.actions.ExplorerAction

@Composable
fun ExplorerActionBar(
    modifier: Modifier = Modifier,
    actions: List<ExplorerAction>,
    onActionClick: (ExplorerAction) -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val availableWidth = maxWidth
        val visibleActions = actions.filter { it.isVisible }

        // Calculate space budget:
        // - Horizontal padding: 16dp (8dp * 2)
        // - Each action button: 48dp
        // - Overflow button (if needed): 48dp
        val horizontalPadding = 16.dp
        val actionButtonWidth = 48.dp
        val overflowButtonWidth = 48.dp

        val spaceForActions = availableWidth - horizontalPadding

        // How many actions can we fit WITHOUT overflow?
        val maxActionsWithoutOverflow = (spaceForActions / actionButtonWidth).toInt()

        val needsOverflow = visibleActions.size > maxActionsWithoutOverflow

        // If we need overflow, reserve space for the overflow button
        val maxVisibleActions = if (needsOverflow) {
            ((spaceForActions - overflowButtonWidth) / actionButtonWidth).toInt().coerceAtLeast(1)
        } else {
            maxActionsWithoutOverflow
        }

        // Split actions by priority (PRIMARY vs SECONDARY)
        val primaryActions = visibleActions.filter { it.group == ExplorerAction.Group.PRIMARY }
        val secondaryActions = visibleActions.filter { it.group == ExplorerAction.Group.SECONDARY }

        // Priority-aware splitting: prefer showing PRIMARY actions
        val (displayedActions, overflowActions) = if (needsOverflow) {
            if (primaryActions.size <= maxVisibleActions) {
                // All primary fit, fill remaining space with secondary
                val secondaryToShow = secondaryActions.take(maxVisibleActions - primaryActions.size)
                (primaryActions + secondaryToShow) to secondaryActions.drop(maxVisibleActions - primaryActions.size)
            } else {
                // Even primary actions overflow
                primaryActions.take(maxVisibleActions) to (primaryActions.drop(maxVisibleActions) + secondaryActions)
            }
        } else {
            visibleActions to emptyList()
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(16.dp)
                ),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                displayedActions.forEach { action ->
                    Box {
                        IconButton(
                            onClick = { onActionClick(action) },
                            enabled = action.isEnabled,
                        ) {
                            Icon(
                                imageVector = action.icon,
                                contentDescription = action.label.get(LocalContext.current),
                                tint = when {
                                    !action.isEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    action.isDestructive -> MaterialTheme.colorScheme.error
                                    else -> LocalContentColor.current
                                }
                            )
                        }

                        if (action.badge) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = (-8).dp, y = 8.dp)
                                    .size(8.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = CircleShape
                                    )
                            )
                        }
                    }
                }

                // Overflow menu for remaining actions
                if (overflowActions.isNotEmpty()) {
                    var showOverflowMenu by remember { mutableStateOf(false) }
                    val hasOverflowBadge = overflowActions.any { it.badge }

                    Box {
                        IconButton(
                            onClick = { showOverflowMenu = true },
                        ) {
                            Icon(
                                imageVector = Icons.TwoTone.MoreVert,
                                contentDescription = stringResource(R.string.explorer_action_more),
                            )
                        }

                        if (hasOverflowBadge) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = (-8).dp, y = 8.dp)
                                    .size(8.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = CircleShape
                                    )
                            )
                        }

                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                        ) {
                            overflowActions.forEach { action ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = action.label.get(LocalContext.current),
                                            color = when {
                                                action.isDestructive -> MaterialTheme.colorScheme.error
                                                else -> LocalContentColor.current
                                            }
                                        )
                                    },
                                    onClick = {
                                        onActionClick(action)
                                        showOverflowMenu = false
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = action.icon,
                                            contentDescription = null,
                                            tint = when {
                                                action.isDestructive -> MaterialTheme.colorScheme.error
                                                else -> LocalContentColor.current
                                            }
                                        )
                                    },
                                    enabled = action.isEnabled,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Preview2
@Composable
fun ExplorerBottomBarNormalModePreview() {
    val mockActions = listOf(
        ExplorerAction.Directory.Create(),
        ExplorerAction.Common.Sort(),
        ExplorerAction.Common.Filter(badge = true),
        ExplorerAction.Common.ToggleView(),
    )

    PreviewWrapper {
        ExplorerActionBar(
            actions = mockActions,
            onActionClick = {},
        )
    }
}

@Preview2
@Composable
fun ExplorerBottomBarSelectionModePreview() {
    val mockActions = listOf(
        ExplorerAction.Directory.Copy(),
        ExplorerAction.Directory.Cut(),
        ExplorerAction.Directory.Delete(),
        ExplorerAction.Directory.Share(),
    )

    PreviewWrapper {
        ExplorerActionBar(
            actions = mockActions,
            onActionClick = {},
        )
    }
}

@Preview2
@Composable
fun ExplorerBottomBarDisabledActionsPreview() {
    val mockActions = listOf(
        ExplorerAction.Directory.Create(isEnabled = false),
        ExplorerAction.Common.Sort(),
        ExplorerAction.Common.Filter(isEnabled = false),
    )

    PreviewWrapper {
        ExplorerActionBar(
            actions = mockActions,
            onActionClick = {},
        )
    }
}

@Preview2
@Composable
fun ExplorerBottomBarOverflowPreview() {
    val mockActions = listOf(
        ExplorerAction.Directory.Create(),
        ExplorerAction.Directory.Copy(),
        ExplorerAction.Directory.Cut(),
        ExplorerAction.Directory.Delete(),
        ExplorerAction.Common.Sort(),
        ExplorerAction.Common.Filter(badge = true),
        ExplorerAction.Common.ToggleView(),
        ExplorerAction.Common.Refresh(),
    )

    PreviewWrapper {
        ExplorerActionBar(
            actions = mockActions,
            onActionClick = {},
        )
    }
}