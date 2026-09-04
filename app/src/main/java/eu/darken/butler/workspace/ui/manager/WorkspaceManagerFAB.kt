package eu.darken.butler.workspace.ui.manager

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material3.FloatingActionButtonDefaults
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.compose.tour.guidedTourTarget
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.defaultArguments
import eu.darken.butler.workspace.ui.manager.tour.WorkspaceManagerTour
import eu.darken.butler.workspace.ui.template.QuickCreateItem

private val StackEdgePadding = 8.dp

@Composable
fun WorkspaceManagerFAB(
    workspaceCount: Int,
    quickCreateItems: List<QuickCreateItem>,
    onQuickCreate: (QuickCreateItem) -> Unit,
    onShowCloseAllDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val expandedState = remember { MutableTransitionState(false) }
    expandedState.targetState = expanded
    val density = LocalDensity.current
    val iconRotation by animateFloatAsState(
        targetValue = if (expanded) 45f else 0f,
        label = "fabIcon",
    )

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
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    modifier = Modifier.rotate(iconRotation),
                    imageVector = Icons.TwoTone.Add,
                    contentDescription = null,
                )
                Text(stringResource(R.string.workspace_fab_quick_shortcuts))
            }
        }

        // A popup rather than an in-bar column: the floating bar stack derives the grid's bottom
        // padding from the bar's measured height, so an expanding bar would shove the grid up
        // exactly where the new-tab card sits.
        if (expandedState.currentState || expandedState.targetState || !expandedState.isIdle) {
            Popup(
                popupPositionProvider = remember(density) {
                    with(density) {
                        FabMenuPositionProvider(
                            gapPx = 8.dp.roundToPx(),
                            edgeInsetPx = StackEdgePadding.roundToPx(),
                        )
                    }
                },
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                AnimatedVisibility(
                    visibleState = expandedState,
                    enter = fadeIn(tween(100)),
                    exit = fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.9f),
                ) {
                    FabActionStack(
                        quickCreateItems = quickCreateItems,
                        showCloseAll = workspaceCount > 1,
                        onQuickCreate = {
                            expanded = false
                            onQuickCreate(it)
                        },
                        onCloseAll = {
                            expanded = false
                            onShowCloseAllDialog()
                        },
                    )
                }
            }
        }
    }
}

internal class FabMenuPositionProvider(
    private val gapPx: Int,
    private val edgeInsetPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = when (layoutDirection) {
            LayoutDirection.Ltr -> anchorBounds.right + edgeInsetPx - popupContentSize.width
            LayoutDirection.Rtl -> anchorBounds.left - edgeInsetPx
        }.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (anchorBounds.top - gapPx - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

@Composable
private fun AnimatedVisibilityScope.FabActionStack(
    modifier: Modifier = Modifier,
    quickCreateItems: List<QuickCreateItem>,
    showCloseAll: Boolean,
    onQuickCreate: (QuickCreateItem) -> Unit,
    onCloseAll: () -> Unit,
) {
    val rowCount = quickCreateItems.size + if (showCloseAll) 1 else 0

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = StackEdgePadding),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        quickCreateItems.forEachIndexed { index, item ->
            StaggeredReveal(rowCount = rowCount, index = index) {
                FabAction(
                    icon = {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                        )
                    },
                    label = item.title.asComposable(),
                    onClick = { onQuickCreate(item) },
                )
            }
        }

        if (showCloseAll) {
            StaggeredReveal(rowCount = rowCount, index = rowCount - 1) {
                FabAction(
                    icon = {
                        Icon(
                            imageVector = Icons.TwoTone.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    label = stringResource(R.string.workspace_fab_close_all),
                    labelColor = MaterialTheme.colorScheme.error,
                    onClick = onCloseAll,
                )
            }
        }
    }
}

/** Rows closest to the button arrive first, so the stack reads as unfolding out of it. */
@Composable
private fun AnimatedVisibilityScope.StaggeredReveal(
    modifier: Modifier = Modifier,
    rowCount: Int,
    index: Int,
    content: @Composable () -> Unit,
) {
    val delay = (rowCount - 1 - index) * 30

    transition.AnimatedVisibility(
        visible = { it == EnterExitState.Visible },
        modifier = modifier,
        enter = fadeIn(tween(150, delayMillis = delay)) +
            slideInVertically(tween(200, delayMillis = delay)) { it / 2 } +
            scaleIn(tween(200, delayMillis = delay), initialScale = 0.8f),
        exit = fadeOut(tween(100)),
    ) {
        content()
    }
}

@Composable
private fun FabAction(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    label: String,
    labelColor: Color = Color.Unspecified,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            icon()
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = labelColor,
            )
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
        onQuickCreate = {},
        onShowCloseAllDialog = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceManagerFABExpandedPreview() {
    AnimatedVisibility(visible = true) {
        FabActionStack(
            quickCreateItems = listOf(
                QuickCreateItem(
                    type = Workspace.Type.EXPLORER,
                    icon = Icons.TwoTone.Folder,
                    title = "Explorer".toCaString(),
                    arguments = Workspace.Type.EXPLORER.defaultArguments!!,
                ),
                QuickCreateItem(
                    type = Workspace.Type.SEARCHER,
                    icon = Icons.TwoTone.Search,
                    title = "Searcher".toCaString(),
                    arguments = Workspace.Type.SEARCHER.defaultArguments!!,
                ),
            ),
            showCloseAll = true,
            onQuickCreate = {},
            onCloseAll = {},
        )
    }
}
