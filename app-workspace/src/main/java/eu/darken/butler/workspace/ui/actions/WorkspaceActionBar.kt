package eu.darken.butler.workspace.ui.actions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.material.icons.twotone.MoreVert
import androidx.compose.material.icons.twotone.Refresh
import androidx.compose.material.icons.twotone.Share
import androidx.compose.material.icons.twotone.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.LocalTooltipsEnabled
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.R
import eu.darken.butler.common.R as CommonR
import eu.darken.butler.workspace.ui.modal.DismissWhenPaneUnfocused

/**
 * Shared action bar for workspace modules
 *
 * Intelligently handles overflow by:
 * - Calculating available width
 * - Prioritizing PRIMARY actions over SECONDARY
 * - Moving actions that don't fit to an overflow menu
 *
 * @param actions List of actions to display
 * @param onActionClick Callback when an action is clicked
 * @param modifier Modifier to apply to the action bar
 */
@Composable
fun WorkspaceActionBar(
    actions: List<WorkspaceActionBarItem>,
    onActionClick: (WorkspaceActionBarItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val context = LocalContext.current
        val availableWidth = maxWidth
        val visibleActions = actions.filter { it.isVisible }

        // Actions that always live in the overflow menu, they never take part in the width budget
        val forcedActions = visibleActions.filter { it.forceOverflow }
        val budgetedActions = visibleActions.filter { !it.forceOverflow }

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

        val needsOverflow = budgetedActions.size > maxActionsWithoutOverflow || forcedActions.isNotEmpty()

        // If we need overflow, reserve space for the overflow button
        val maxVisibleActions = if (needsOverflow) {
            ((spaceForActions - overflowButtonWidth) / actionButtonWidth).toInt().coerceAtLeast(1)
        } else {
            maxActionsWithoutOverflow
        }

        // Split actions by priority (PRIMARY vs SECONDARY)
        val primaryActions = budgetedActions.filter { it.group == WorkspaceActionBarItem.Group.PRIMARY }
        val secondaryActions = budgetedActions.filter { it.group == WorkspaceActionBarItem.Group.SECONDARY }

        // Priority-aware splitting: prefer showing PRIMARY actions
        val (displayedActions, budgetedOverflowActions) = if (needsOverflow) {
            if (primaryActions.size <= maxVisibleActions) {
                // All primary fit, fill remaining space with secondary
                val secondaryToShow = secondaryActions.take(maxVisibleActions - primaryActions.size)
                (primaryActions + secondaryToShow) to secondaryActions.drop(maxVisibleActions - primaryActions.size)
            } else {
                // Even primary actions overflow
                primaryActions.take(maxVisibleActions) to (primaryActions.drop(maxVisibleActions) + secondaryActions)
            }
        } else {
            budgetedActions to emptyList()
        }
        val overflowActions = budgetedOverflowActions + forcedActions

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
                    // Keyed so tooltip state can't migrate between actions when the list reorders
                    key(action) {
                        Box(
                            modifier = Modifier.size(48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            // Outside the clickable, so disabled actions still show their label on hold
                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                                    TooltipAnchorPosition.Above,
                                ),
                                tooltip = { PlainTooltip { Text(action.label.get(context)) } },
                                state = rememberTooltipState(),
                                enableUserInput = LocalTooltipsEnabled.current,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .clickable(
                                            enabled = action.isEnabled,
                                            onClick = { onActionClick(action) },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = action.icon,
                                        contentDescription = action.label.get(context),
                                        tint = when {
                                            !action.isEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                            action.isDestructive -> MaterialTheme.colorScheme.error
                                            else -> LocalContentColor.current
                                        },
                                    )
                                }
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
                                contentDescription = stringResource(R.string.workspace_action_more),
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

                        DismissWhenPaneUnfocused(expanded = showOverflowMenu) { showOverflowMenu = false }
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
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceActionBarPreview() {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(16.dp)
    ) {
        // Sample action implementation for preview
        data class SampleActionBarItem(
            override val icon: ImageVector,
            override val label: CaString,
            override val isVisible: Boolean = true,
            override val isEnabled: Boolean = true,
            override val isDestructive: Boolean = false,
            override val group: WorkspaceActionBarItem.Group = WorkspaceActionBarItem.Group.PRIMARY,
            override val badge: Boolean = false,
        ) : WorkspaceActionBarItem

        // Basic action bar with a few actions
        WorkspaceActionBar(
            actions = listOf(
                SampleActionBarItem(Icons.TwoTone.Share, CommonR.string.general_share_action.toCaString()),
                SampleActionBarItem(Icons.TwoTone.Edit, CommonR.string.general_edit_action.toCaString()),
                SampleActionBarItem(
                    Icons.TwoTone.Delete,
                    CommonR.string.general_delete_action.toCaString(),
                    isDestructive = true
                ),
            ),
            onActionClick = {},
        )

        // With overflow menu (more actions than fit)
        WorkspaceActionBar(
            actions = listOf(
                SampleActionBarItem(Icons.TwoTone.Share, CommonR.string.general_share_action.toCaString()),
                SampleActionBarItem(Icons.TwoTone.Edit, CommonR.string.general_edit_action.toCaString()),
                SampleActionBarItem(Icons.TwoTone.ContentCopy, CommonR.string.general_copy_action.toCaString()),
                SampleActionBarItem(
                    Icons.TwoTone.Star,
                    CommonR.string.general_view_action.toCaString(),
                    group = WorkspaceActionBarItem.Group.SECONDARY
                ),
                SampleActionBarItem(
                    Icons.TwoTone.Refresh,
                    CommonR.string.general_refresh_action.toCaString(),
                    group = WorkspaceActionBarItem.Group.SECONDARY
                ),
                SampleActionBarItem(
                    Icons.TwoTone.Delete,
                    CommonR.string.general_delete_action.toCaString(),
                    isDestructive = true
                ),
            ),
            onActionClick = {},
        )

        // With badges and disabled action
        WorkspaceActionBar(
            actions = listOf(
                SampleActionBarItem(
                    Icons.TwoTone.Refresh,
                    CommonR.string.general_refresh_action.toCaString(),
                    badge = true
                ),
                SampleActionBarItem(
                    Icons.TwoTone.Edit,
                    CommonR.string.general_edit_action.toCaString(),
                    isEnabled = false
                ),
                SampleActionBarItem(
                    Icons.TwoTone.Delete,
                    CommonR.string.general_delete_action.toCaString(),
                    isDestructive = true
                ),
            ),
            onActionClick = {},
        )
    }
}
