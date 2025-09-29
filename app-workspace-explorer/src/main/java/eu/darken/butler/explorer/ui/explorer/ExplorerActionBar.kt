package eu.darken.butler.explorer.ui.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Stars
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.R
import eu.darken.butler.common.compose.ButlerIcon
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.explorer.ui.explorer.actions.ExplorerAction

@Composable
fun ExplorerActionBar(
    modifier: Modifier = Modifier,
    actions: List<ExplorerAction>,
    onActionClick: (ExplorerAction) -> Unit,
    onButlerIconClick: () -> Unit,
    isPro: Boolean = false,
) {
    Card(
        modifier = modifier
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
        val visibleActions = actions.filter { it.isVisible }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onButlerIconClick,
            ) {
                if (isPro) {
                    ButlerIcon(
                        size = 24.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.TwoTone.Stars,
                        contentDescription = stringResource(R.string.general_upgrade_action),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                visibleActions.forEach { action ->
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
            onButlerIconClick = {},
            isPro = true,
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
            onButlerIconClick = {},
            isPro = true,
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
            onButlerIconClick = {},
            isPro = true,
        )
    }
}

@Preview2
@Composable
fun ExplorerBottomBarNonProPreview() {
    val mockActions = listOf(
        ExplorerAction.Directory.Create(),
        ExplorerAction.Common.Sort(),
        ExplorerAction.Common.Filter(),
        ExplorerAction.Common.ToggleView(),
    )

    PreviewWrapper {
        ExplorerActionBar(
            actions = mockActions,
            onActionClick = {},
            onButlerIconClick = {},
            isPro = false,
        )
    }
}