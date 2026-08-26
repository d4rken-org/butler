package eu.darken.butler.workspace.ui.workspaces.adaptive

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.DragIndicator
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.material.icons.twotone.Looks3
import androidx.compose.material.icons.twotone.Looks4
import androidx.compose.material.icons.twotone.LooksOne
import androidx.compose.material.icons.twotone.LooksTwo
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.systemBarsWithOptionalCutout
import eu.darken.butler.common.compose.tour.guidedTourTarget
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.ui.common.CutoutTopRightCornerShape
import eu.darken.butler.workspace.ui.manager.PaneLayoutGlyph
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.manager.paneCells
import eu.darken.butler.workspace.ui.tour.WorkspaceTourTargets
import eu.darken.butler.workspace.ui.workspaces.WorkspacePaneInfo
import eu.darken.butler.workspace.ui.workspaces.asPaneInfo
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.common.R as CommonR

object WorkspaceNavigationRailDefaults {
    const val SURFACE_TEST_TAG = "workspace.rail.surface"
    const val CONTENT_TEST_TAG = "workspace.rail.content"
    const val LIST_TEST_TAG = "workspace.rail.list"

    /**
     * On the entry's card, not on its outer box: the card is the node that carries the click, the
     * selection state and the pane description, and a tag on the box would address a different node
     * than the one under test.
     */
    const val ITEM_TEST_TAG = "workspace.rail.item"
}

private val RailSectionPadding = 8.dp
private val RailItemHeight = 56.dp
private val RailItemSpacing = 4.dp
private val RailItemInset = 8.dp
private val RailItemCornerRadius = 8.dp
private val RailItemShape = RoundedCornerShape(RailItemCornerRadius)

/**
 * The notch is bounded by the type icon, which [RailIconNotchShift] centres in the width left over.
 * At 23dp the icon's box ends 10dp clear of it. Height is not contended - the shape would allow 36dp
 * - so the notch is square and the glyph inside it is too, which is what makes a quad grid's cells
 * readable at this size.
 *
 * Its transition corner is deliberately tighter than the entry's own - see [CutoutTopRightCornerShape].
 *
 * Declared before the values derived from it: top-level properties initialise in file order, so a
 * later declaration would read as 0.dp here without any complaint from the compiler.
 */
private val RailItemNotchWidth = 23.dp

/**
 * Centres the type icon in the width the notch leaves rather than in the whole entry, which is a
 * shift of exactly half the notch - the icon's box is centred, so moving its centre from `W/2` to
 * `(W - notch)/2` is `notch/2`. Derived rather than tuned, so the two cannot drift apart.
 */
private val RailIconNotchShift = RailItemNotchWidth / 2

/**
 * The type icon gives up a little size as well as position when a notch opens, so the assigned state
 * reads as distinct rather than merely shifted. Not a clearance fix - [RailIconNotchShift] already
 * leaves the icon 8.5dp clear of the notch at full size - which is why the difference stays at 4dp:
 * assigned and unassigned entries sit in one list, and a bigger gap starts to look like two icon
 * sets rather than one icon in two states.
 */
private val RailIconSize = 24.dp
private val RailIconNotchedSize = 20.dp

private val RailItemNotchedShape = CutoutTopRightCornerShape(
    cutoutWidth = RailItemNotchWidth,
    cutoutHeight = RailItemNotchWidth,
    cornerRadius = RailItemCornerRadius,
    cutoutCornerRadius = 4.dp,
    transitionCornerRadius = 4.dp,
)

/** Centres the 19x19dp glyph inside the 23x23dp notch. */
private val RailNotchGlyphPadding = PaddingValues(top = 2.dp, end = 2.dp)

/**
 * What the reveal effect restarts on: which workspace is focused and where the entries sit.
 *
 * Deliberately NOT the [Workspace.Info] list itself - those carry titles, lifecycle states,
 * operation counts and badges, so keying on them would restart the effect on every unrelated
 * update and yank a rail the user just scrolled.
 */
internal data class RailRevealKey(
    val focusedId: Workspace.Id?,
    val orderedIds: List<Workspace.Id>,
)

internal fun railRevealKey(focusedId: Workspace.Id?, workspaces: List<Workspace.Info>) =
    RailRevealKey(focusedId = focusedId, orderedIds = workspaces.map { it.id })

/**
 * True when the focused entry is not fully on screen and nothing the user is doing should be
 * interrupted. A partially visible entry counts as needing reveal: a card clipped to a sliver at the
 * edge of the rail reads as "nothing happened" to the user who just picked that tab, so only
 * [fullyVisibleIndices] - entries within the viewport in their entirety - may suppress the scroll.
 * [isScrolling] covers an ordinary scroll or fling, which [isDragging] (reorder only) does not.
 */
internal fun shouldRevealFocused(
    focusedIndex: Int,
    fullyVisibleIndices: List<Int>,
    isDragging: Boolean,
    isScrolling: Boolean,
): Boolean = focusedIndex >= 0 && !isDragging && !isScrolling && focusedIndex !in fullyVisibleIndices

@Composable
fun WorkspaceNavigationRail(
    modifier: Modifier = Modifier,
    workspaces: List<Workspace.Info>,
    selected: Map<Int, WorkspacePaneInfo>,
    focusedId: Workspace.Id?,
    design: WorkspaceDesign = WorkspaceDesign(),
    onTabAction: (WorkspaceAction) -> Unit,
    onPaneAssignment: (workspaceId: Workspace.Id, paneIndex: Int) -> Unit,
    onRename: (Workspace.Id) -> Unit = {},
    onPaneMenuToggle: (Boolean) -> Unit = {},
) {
    // Local state for reordering
    var localWorkspaces by remember { mutableStateOf(workspaces) }
    var isDragging by remember { mutableStateOf(false) }

    // Update local workspaces when input changes and not dragging
    if (!isDragging && localWorkspaces != workspaces) {
        localWorkspaces = workspaces
    }

    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(
        lazyListState = lazyListState
    ) { from, to ->
        val fromId = from.key as? Workspace.Id
        val toId = to.key as? Workspace.Id

        if (fromId != null && toId != null) {
            val fromIndex = localWorkspaces.indexOfFirst { it.id == fromId }
            val toIndex = localWorkspaces.indexOfFirst { it.id == toId }

            if (fromIndex != -1 && toIndex != -1) {
                val mutableList = localWorkspaces.toMutableList()
                val movedItem = mutableList.removeAt(fromIndex)
                mutableList.add(toIndex, movedItem)
                localWorkspaces = mutableList
            }
        }
    }

    // A workspace can gain focus without being on screen (opened from a pane far down the list), and
    // an off-screen focused entry leaves the rail looking like nothing happened.
    val revealKey = railRevealKey(focusedId, localWorkspaces)
    LaunchedEffect(revealKey) {
        val focusedIndex = revealKey.orderedIds.indexOf(revealKey.focusedId)
        val layoutInfo = lazyListState.layoutInfo
        // visibleItemsInfo includes items clipped by the viewport, so filter down to the entries that
        // fit inside it completely.
        val fullyVisibleIndices = layoutInfo.visibleItemsInfo
            .filter { it.offset >= layoutInfo.viewportStartOffset && it.offset + it.size <= layoutInfo.viewportEndOffset }
            .map { it.index }
        val shouldReveal = shouldRevealFocused(
            focusedIndex = focusedIndex,
            fullyVisibleIndices = fullyVisibleIndices,
            isDragging = isDragging,
            isScrolling = lazyListState.isScrollInProgress,
        )
        if (shouldReveal) lazyListState.animateScrollToItem(focusedIndex)
    }

    WorkspaceRailContainer(modifier = modifier) {
        // Unconditional: the rail exists once per screen, and only in multi-pane - where the
        // Templates page renders no Butler button of its own.
        WorkspaceButton(
            modifier = Modifier
                .padding(vertical = RailSectionPadding)
                .guidedTourTarget(WorkspaceTourTargets.BUTLER_BUTTON),
            currentWorkspaceId = focusedId,
        )

        HorizontalDivider()

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = RailSectionPadding)
                .testTag(WorkspaceNavigationRailDefaults.LIST_TEST_TAG),
            state = lazyListState,
            verticalArrangement = Arrangement.spacedBy(RailItemSpacing),
        ) {
            items(
                items = localWorkspaces,
                key = { it.id }
            ) { ws ->
                ReorderableItem(
                    reorderableLazyListState,
                    key = ws.id
                ) { isDraggingItem ->
                    val paneIndex = selected.entries.find { it.value.id == ws.id }?.key
                    DraggableWorkspaceRailItem(
                        workspace = ws,
                        isFocused = focusedId == ws.id,
                        currentPaneIndex = paneIndex,
                        onTabAction = onTabAction,
                        onPaneAssignment = onPaneAssignment,
                        onRename = onRename,
                        design = design,
                        onPaneMenuToggle = onPaneMenuToggle,
                        isDraggingItem = isDraggingItem,
                        onDragStarted = {
                            isDragging = true
                        },
                        onDragStopped = {
                            isDragging = false
                            // Trigger reorder action with new order
                            val newOrder = localWorkspaces.map { it.id }
                            onTabAction(WorkspaceAction.Reorder(newOrder))
                        },
                        reorderableScope = this,
                    )
                }
            }
        }

        HorizontalDivider()

        Spacer(modifier = Modifier.height(RailSectionPadding))

        FloatingActionButton(
            onClick = {
                onTabAction(
                    WorkspaceAction.Create()
                )
            },
            modifier = Modifier.size(48.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Icon(
                imageVector = Icons.TwoTone.Add,
                contentDescription = stringResource(R.string.workspace_add_tab_description),
            )
        }

        Spacer(modifier = Modifier.height(RailSectionPadding))
    }
}

@Composable
internal fun WorkspaceRailContainer(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .testTag(WorkspaceNavigationRailDefaults.SURFACE_TEST_TAG),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                // Only the start side: the rail doesn't touch the end edge, and padding for an end-side
                // navigation bar here would widen the rail while the end pane pads for it as well.
                .windowInsetsPadding(
                    systemBarsWithOptionalCutout()
                        .only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)
                )
                .width(80.dp)
                .testTag(WorkspaceNavigationRailDefaults.CONTENT_TEST_TAG)
                // Inside the tagged 80dp, so the rail's own width is unaffected. Kept tight because
                // the entry's spare width is what the pane glyph's notch is carved out of, and what
                // the label has left after the type icon.
                .padding(horizontal = RailItemInset),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

/**
 * Rail entry with its own background: nothing when the workspace sits idle, an outline once it is
 * assigned to a pane, a filled container while it is the focused one.
 *
 * An assigned entry also loses its top-trailing corner to a notch holding a [PaneLayoutGlyph]. The
 * glyph has to be a sibling of the [Surface] rather than its child, because a Surface clips content
 * to its shape and the notch is by definition outside it - hence the wrapping [Box], which exists
 * purely to position the two and is deliberately semantics-free.
 *
 * The glyph is decorative and the pane it depicts is announced by the Surface instead, so that the
 * entry stays one node for TalkBack. Merging at the Box would not achieve that: `Surface(onClick)`
 * is itself a merging node, so an enclosing merging node cannot absorb it, and the entry would
 * announce twice - once for the glyph, once for the clickable card.
 *
 * [modifier] therefore goes on the Box: everything that lays the entry out or paints it has to move
 * the card and the glyph together, so it cannot live on the card alone.
 */
@Composable
internal fun WorkspaceRailItem(
    modifier: Modifier = Modifier,
    workspace: Workspace.Info,
    paneIndex: Int?,
    isFocused: Boolean,
    layout: WorkspaceDesign.Layout = WorkspaceDesign.Layout.SINGLE,
    isDraggingItem: Boolean = false,
    dragHandleModifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val isAssigned = paneIndex != null
    // A single pane makes the glyph a decorative dot - the fill and outline already say "assigned" -
    // and a dragging entry swaps its icon out for the drag handle, so it drops the notch too. A pane
    // index the layout no longer has (an assignment outliving a rotation) simply gets no glyph.
    val cells = remember(layout) { paneCells(layout) }
    val glyphPaneIndex = paneIndex?.takeIf { !isDraggingItem && cells.size > 1 && it in cells.indices }

    val restingContainerColor by animateColorAsState(
        targetValue = if (isFocused) colorScheme.secondaryContainer else Color.Transparent,
    )
    // A dragging item is always opaque, in every state: the elevation shadow is drawn from the
    // shape, not the fill, so a see-through card would carry a shadow and show other items through
    // itself. Both dragging fills bypass the resting animator entirely, so fill and elevation can
    // never be out of step - not even when focus changes mid-drag. The spring scale below supplies
    // the motion cue for the lift.
    val containerColor = when {
        isDraggingItem && isFocused -> colorScheme.secondaryContainer
        isDraggingItem -> colorScheme.surface
        else -> restingContainerColor
    }
    val shadowElevation = if (isDraggingItem) 6.dp else 0.dp
    val scale by animateFloatAsState(
        targetValue = if (isDraggingItem) 1.05f else 1f,
        animationSpec = spring(),
    )
    // Animated rather than snapped: gaining a pane already changes the entry's shape and border, so
    // the icon sliding aside reads as part of that one movement instead of a jump. Offset, not
    // padding, so it costs no layout pass - and Modifier.offset resolves against the layout
    // direction, which keeps the icon moving away from the notch under RTL too.
    val iconNotchShift by animateDpAsState(
        targetValue = if (glyphPaneIndex != null) RailIconNotchShift else 0.dp,
    )
    val iconSize by animateDpAsState(
        targetValue = if (glyphPaneIndex != null) RailIconNotchedSize else RailIconSize,
    )

    val paneDescription = glyphPaneIndex
        ?.let { stringResource(R.string.workspace_pane_current_description, it + 1) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale),
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                // A minimum rather than a fixed height: the label's line box is sized in sp, so at
                // a font scale above 1 a fixed entry would clip its own text. The Box wraps the card
                // instead of sizing it, so the notch glyph keeps aligning to the card's corner.
                .heightIn(min = RailItemHeight)
                .testTag(WorkspaceNavigationRailDefaults.ITEM_TEST_TAG)
                .semantics {
                    selected = isAssigned
                    role = Role.Tab
                    paneDescription?.let { contentDescription = it }
                },
            shape = if (glyphPaneIndex != null) RailItemNotchedShape else RailItemShape,
            color = containerColor,
            contentColor = when {
                isFocused -> colorScheme.onSecondaryContainer
                isAssigned -> colorScheme.onSurface
                else -> colorScheme.onSurfaceVariant
            },
            border = if (isAssigned && !isFocused) BorderStroke(1.dp, colorScheme.outline) else null,
            shadowElevation = shadowElevation,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    // Only the icon row steps aside, not the label: the notch takes width from the
                    // top of the entry and the label sits below it with the full width still. Same
                    // principle as CutoutAwareColumn, which the toolbar cards use.
                    modifier = dragHandleModifier.offset(x = -iconNotchShift),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        modifier = Modifier.size(iconSize),
                        imageVector = if (isDraggingItem) Icons.TwoTone.DragIndicator else workspace.type.icon,
                        contentDescription = if (isDraggingItem) {
                            stringResource(R.string.workspace_dragging_description)
                        } else {
                            null
                        },
                    )
                }
                Text(
                    modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 4.dp, top = 4.dp),
                    text = workspace.displayTitle.get(LocalContext.current),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
        }

        glyphPaneIndex?.let { index ->
            PaneLayoutGlyph(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(RailNotchGlyphPadding),
                layout = layout,
                paneIndex = index,
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceRailItemPreview() {
    WorkspaceRailItemStates(layout = WorkspaceDesign.Layout.TRIPLE_MAIN_LEFT)
}

/**
 * The notch has to disappear when a single pane makes the glyph meaningless.
 */
@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceRailItemSinglePanePreview() {
    WorkspaceRailItemStates(layout = WorkspaceDesign.Layout.SINGLE)
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceRailItemRtlPreview() {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        WorkspaceRailItemStates(layout = WorkspaceDesign.Layout.TRIPLE_MAIN_LEFT)
    }
}

@Composable
private fun WorkspaceRailItemStates(layout: WorkspaceDesign.Layout) {
    Column(
        modifier = Modifier
            .width(80.dp)
            .padding(horizontal = RailItemInset),
        verticalArrangement = Arrangement.spacedBy(RailItemSpacing),
    ) {
        WorkspaceRailItem(
            workspace = Workspace.Info(
                id = Workspace.Id(),
                type = Workspace.Type.EXPLORER,
                title = "Explorer".toCaString(),
            ),
            paneIndex = null,
            isFocused = false,
            layout = layout,
            onClick = {},
        )
        // Assigned but not focused: the border has to follow the notch.
        WorkspaceRailItem(
            workspace = Workspace.Info(
                id = Workspace.Id(),
                type = Workspace.Type.SEARCHER,
                title = "Search".toCaString(),
            ),
            paneIndex = 1,
            isFocused = false,
            layout = layout,
            onClick = {},
        )
        WorkspaceRailItem(
            workspace = Workspace.Info(
                id = Workspace.Id(),
                type = Workspace.Type.EDITOR,
                title = "Editor".toCaString(),
            ),
            paneIndex = 0,
            isFocused = true,
            layout = layout,
            onClick = {},
        )
        WorkspaceRailItem(
            workspace = Workspace.Info(
                id = Workspace.Id(),
                type = Workspace.Type.TEMPLATES,
                title = "Templates".toCaString(),
            ),
            paneIndex = 2,
            isFocused = false,
            layout = layout,
            isDraggingItem = true,
            onClick = {},
        )
    }
}

@Composable
private fun DraggableWorkspaceRailItem(
    workspace: Workspace.Info,
    isFocused: Boolean,
    currentPaneIndex: Int?,
    onTabAction: (WorkspaceAction) -> Unit,
    onPaneAssignment: (workspaceId: Workspace.Id, paneIndex: Int) -> Unit,
    onRename: (Workspace.Id) -> Unit,
    design: WorkspaceDesign,
    onPaneMenuToggle: (Boolean) -> Unit,
    isDraggingItem: Boolean,
    onDragStarted: () -> Unit,
    onDragStopped: () -> Unit,
    reorderableScope: sh.calvin.reorderable.ReorderableCollectionItemScope,
) {
    val hapticFeedback = LocalHapticFeedback.current
    var showPaneMenu by remember { mutableStateOf(false) }

    LaunchedEffect(showPaneMenu) {
        onPaneMenuToggle(showPaneMenu)
    }

    Box {
        WorkspaceRailItem(
            workspace = workspace,
            paneIndex = currentPaneIndex,
            isFocused = isFocused,
            layout = design.layout,
            isDraggingItem = isDraggingItem,
            dragHandleModifier = with(reorderableScope) {
                // Long press, not press: the handle drags along the list's own scroll axis, so a
                // press-based detector turns every scroll that starts on an item into a reorder.
                Modifier.longPressDraggableHandle(
                    onDragStarted = {
                        onDragStarted()
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDragStopped = {
                        onDragStopped()
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                    },
                )
            },
            onClick = { showPaneMenu = true },
        )

        DropdownMenu(
            expanded = showPaneMenu,
            onDismissRequest = { showPaneMenu = false },
        ) {
            repeat(design.maxPanes) { paneIndex ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.workspace_pane_assign_action, paneIndex + 1)) },
                    leadingIcon = {
                        Icon(
                            imageVector = when (paneIndex) {
                                0 -> Icons.TwoTone.LooksOne
                                1 -> Icons.TwoTone.LooksTwo
                                2 -> Icons.TwoTone.Looks3
                                else -> Icons.TwoTone.Looks4
                            },
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        showPaneMenu = false
                        onPaneMenuToggle(false)  // Explicitly hide overlays
                        onPaneAssignment(workspace.id, paneIndex)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(CommonR.string.general_rename_action)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.TwoTone.Edit,
                        contentDescription = null,
                    )
                },
                onClick = {
                    showPaneMenu = false
                    onPaneMenuToggle(false)
                    onRename(workspace.id)
                },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            DropdownMenuItem(
                text = { Text(stringResource(R.string.workspace_pane_close_action)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.TwoTone.Close,
                        contentDescription = null,
                    )
                },
                onClick = {
                    showPaneMenu = false
                    onPaneMenuToggle(false)  // Explicitly hide overlays before closing
                    onTabAction(WorkspaceAction.Close(workspace.id, undoable = true))
                },
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceNavigationRailPreview() {
    val tabs = listOf(
        Workspace.Info(
            id = Workspace.Id(),
            type = Workspace.Type.EXPLORER,
            title = "Explorer 1234".toCaString(),
        ),
        Workspace.Info(
            id = Workspace.Id(),
            type = Workspace.Type.SEARCHER,
            title = "Search 1234".toCaString(),
        ),
        Workspace.Info(
            id = Workspace.Id(),
            type = Workspace.Type.EDITOR,
            title = "Editor 1234".toCaString(),
        ),
    )
    WorkspaceNavigationRail(
        workspaces = tabs,
        selected = mapOf(0 to tabs[0].asPaneInfo(), 1 to tabs[1].asPaneInfo()),
        focusedId = tabs[0].id,
        // The rail only exists in multi-pane mode, so previewing the default SINGLE would show a
        // rail that can never occur - and no glyphs.
        design = WorkspaceDesign(layout = WorkspaceDesign.Layout.TRIPLE_MAIN_LEFT),
        onTabAction = {},
        onPaneAssignment = { _, _ -> },
        onPaneMenuToggle = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PaneMenuPreview() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        DropdownMenu(
            expanded = true,
            onDismissRequest = {},
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.workspace_pane_assign_action, 1)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.TwoTone.LooksOne,
                        contentDescription = null,
                    )
                },
                onClick = {},
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.workspace_pane_assign_action, 2)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.TwoTone.LooksTwo,
                        contentDescription = null,
                    )
                },
                onClick = {},
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.workspace_pane_assign_action, 3)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.TwoTone.Looks3,
                        contentDescription = null,
                    )
                },
                onClick = {},
            )
            // Four entries, because pane 4 used to fall back to the "1" icon.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.workspace_pane_assign_action, 4)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.TwoTone.Looks4,
                        contentDescription = null,
                    )
                },
                onClick = {},
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            DropdownMenuItem(
                text = { Text(stringResource(R.string.workspace_pane_close_action)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.TwoTone.Close,
                        contentDescription = null,
                    )
                },
                onClick = {},
            )
        }
    }
}