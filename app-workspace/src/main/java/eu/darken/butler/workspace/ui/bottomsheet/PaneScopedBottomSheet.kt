package eu.darken.butler.workspace.ui.bottomsheet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
import eu.darken.butler.workspace.ui.insets.LocalPaneEdges
import eu.darken.butler.workspace.ui.insets.paneHorizontalInsetPadding
import eu.darken.butler.workspace.ui.modal.PaneLayer
import eu.darken.butler.workspace.ui.modal.WorkspaceBackHandler
import eu.darken.butler.workspace.ui.modal.requestPaneFocusOnPress
import kotlin.math.min
import kotlin.math.roundToInt

object PaneScopedBottomSheetDefaults {
    const val SCRIM_TEST_TAG = "workspace.sheet.panescoped.scrim"
    const val CARD_TEST_TAG = "workspace.sheet.panescoped.card"
}

/**
 * Who bounds and scrolls a sheet's content area.
 */
enum class SheetContentScroll {
    /**
     * The sheet bounds its content to the pane and scrolls it. Correct for content that just stacks
     * its children, which is almost everything.
     */
    SheetOwned,

    /**
     * The content bounds and scrolls itself; the sheet only wraps it.
     *
     * Content with a root `verticalScroll` or an unbounded lazy container **must** declare this —
     * left on [SheetOwned] it nests two scrollers on the same axis, which crashes.
     */
    ContentOwned,
}

/**
 * A bottom sheet that is scoped to a specific workspace pane instead of being a global window-level overlay.
 *
 * Unlike [androidx.compose.material3.ModalBottomSheet], this component:
 * - Renders within the pane's composable hierarchy
 * - Only applies scrim/overlay within the pane (not full-screen)
 * - Allows interaction with other panes in multi-pane layouts
 * - Allows swiping between workspaces while the sheet remains in its pane
 * - Supports drag-to-dismiss gesture
 *
 * @param visible Whether the bottom sheet should be shown
 * @param onDismiss Callback when the user dismisses the sheet (by clicking the scrim or dragging down)
 * @param contentScroll Who owns the content's height bound and scrolling, see [SheetContentScroll].
 *        The default bounds the content to the pane and scrolls it, so content taller than the pane
 *        stays reachable.
 * @param contentKey Identity of the content currently being shown. A sheet that stays [visible]
 *        while its content is replaced (e.g. the next file conflict) passes the new identity here so
 *        the content starts at the top instead of inheriting the previous scroll offset.
 * @param dragHandle Optional drag handle composable. Pass null to hide the handle.
 * @param includeImePadding Whether the sheet content should pad for the soft keyboard. Enable
 *        only for sheets containing an editable text field. When `false` the sheet ignores the
 *        IME (so a stale host IME inset can't inflate it) and dismisses the keyboard on show.
 * @param modifier Modifier for the sheet content
 * @param content The content to display in the bottom sheet
 */
@Composable
fun PaneScopedBottomSheet(
    modifier: Modifier = Modifier,
    visible: Boolean,
    onDismiss: () -> Unit,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
    includeImePadding: Boolean = false,
    contentScroll: SheetContentScroll = SheetContentScroll.SheetOwned,
    contentKey: Any? = null,
    dragHandle: @Composable (() -> Unit)? = { DefaultDragHandle() },
    content: @Composable () -> Unit,
) {
    // In preview mode, just show the content as a card
    if (LocalInspectionMode.current) {
        if (!visible) return
        SheetCard(
            modifier = modifier,
            bottomInset = bottomInset,
            includeImePadding = includeImePadding,
            contentScroll = contentScroll,
            scrollState = rememberScrollState(),
            dragHandle = dragHandle,
            content = content,
        )
        return
    }

    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val density = LocalDensity.current
    val dragState = remember(density) {
        SheetDragState(
            dismissThresholdPx = with(density) { 100.dp.toPx() },
            velocityThreshold = 1000f,
        )
    }

    // Reset offset when becoming visible
    LaunchedEffect(visible) {
        if (visible) dragState.snapToRest()
    }

    val scrollState = rememberScrollState()
    // Reopening a hidden sheet, or swapping in new content while it stays visible, starts at the
    // top. A configuration change must not: it re-runs this effect on a composition whose scroll
    // offset was just restored, so the first pass after (re)creation is deliberately skipped.
    val resetArmed = remember { mutableStateOf(false) }
    LaunchedEffect(visible, contentKey) {
        if (!resetArmed.value) {
            resetArmed.value = true
            return@LaunchedEffect
        }
        if (visible) scrollState.scrollTo(0)
    }

    // A non-input sheet shouldn't sit behind a keyboard, nor inherit a stale host IME inset that
    // can linger after a dialog's keyboard is dismissed. Hide the keyboard when such a sheet
    // appears. Gated on workspace focus so a sheet opening in an unfocused pane can't steal the
    // keyboard from a focused pane (e.g. the editor). Keyed only on (visible, includeImePadding)
    // so nested dialogs (e.g. PathIssueRenameDialog) don't re-fire it.
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val isWorkspaceFocused = LocalWorkspaceFocused.current
    LaunchedEffect(visible, includeImePadding) {
        if (visible && !includeImePadding && isWorkspaceFocused) {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    // The sheet stays on screen for the ~200ms exit transition after `visible` goes false, so layer
    // registration follows the transition rather than `visible` — otherwise the content behind
    // would become interactive again while the sheet is still covering it.
    val transition = updateTransition(targetState = visible, label = "PaneScopedBottomSheet")
    val layerPresent = transition.currentState || transition.targetState

    // Same reasoning as the dialog: a sheet without a text field hides the keyboard on show, so it
    // must not have focus pushed into it either.
    PaneLayer(
        modifier = Modifier.fillMaxSize(),
        takeFocus = includeImePadding,
        enabled = layerPresent,
    ) {
        // Must live inside the layer and follow the same lifetime: composed outside it, this would
        // read the layer *below* the sheet, and gating it on `visible` would disable it during the
        // exit transition while the page handlers underneath are still deactivated — leaving back
        // to fall through to the activity's exit handler.
        WorkspaceBackHandler(enabled = layerPresent, onBack = onDismiss)

        // Scrim overlay (pane-local, not full-screen)
        transition.AnimatedVisibility(
            visible = { it },
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(200)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(PaneScopedBottomSheetDefaults.SCRIM_TEST_TAG)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .requestPaneFocusOnPress()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )
        }

        // Bottom sheet content. Inset here, not on the scrim above: the scrim has to keep covering
        // the strip next to a side navigation bar or a cutout.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .paneHorizontalInsetPadding(LocalPaneEdges.current)
                .padding(top = topInset),
            contentAlignment = Alignment.BottomCenter
        ) {
            // Already measured inside the top padding — subtracting the inset again would bound the
            // card to less than the space it actually has.
            val maxSheetHeight = maxHeight

            transition.AnimatedVisibility(
                visible = { it },
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(200)
                )
            ) {
                val nestedScrollConnection = remember(dragState) {
                    SheetNestedScrollConnection(
                        dragState = dragState,
                        onDismiss = { currentOnDismiss() },
                    )
                }

                SheetCard(
                    // The tag sits *below* the drag offset on purpose: a semantics node reports the
                    // bounds of its own place in the chain, so tagged above it the card would keep
                    // reporting its resting position while it is being dragged.
                    modifier = modifier
                        .requestPaneFocusOnPress()
                        .offset { IntOffset(0, dragState.offset.roundToInt()) }
                        .testTag(PaneScopedBottomSheetDefaults.CARD_TEST_TAG)
                        .nestedScroll(nestedScrollConnection),
                    // The handle keeps a drag path of its own: the nested-scroll route only reaches
                    // the sheet once a child scroller is at its top, and the handle must stay
                    // grabbable no matter where the content is scrolled to.
                    handleModifier = Modifier.draggable(
                        state = rememberDraggableState { delta -> dragState.dragBy(delta) },
                        orientation = Orientation.Vertical,
                        onDragStopped = { velocity ->
                            if (dragState.settle(velocity)) currentOnDismiss()
                        },
                    ),
                    maxHeight = maxSheetHeight,
                    bottomInset = bottomInset,
                    includeImePadding = includeImePadding,
                    contentScroll = contentScroll,
                    scrollState = scrollState,
                    dragHandle = dragHandle,
                    content = content,
                )
            }
        }
    }
}

/**
 * The card, the handle and the content region — shared by the real sheet and the preview branch, so
 * a preview cannot drift into a different shape than production.
 *
 * [maxHeight] is [Dp.Unspecified] in previews, where there is no pane to measure against. That also
 * decides whether the content region is weighted: a `weight` in a column with an unbounded main
 * axis measures to zero height, so an unbounded card shows its content at full length instead.
 */
@Composable
private fun SheetCard(
    modifier: Modifier = Modifier,
    handleModifier: Modifier = Modifier,
    maxHeight: Dp = Dp.Unspecified,
    bottomInset: Dp = 0.dp,
    includeImePadding: Boolean = false,
    contentScroll: SheetContentScroll = SheetContentScroll.SheetOwned,
    scrollState: ScrollState,
    dragHandle: @Composable (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .then(if (includeImePadding) Modifier.imePadding() else Modifier)
                .padding(bottom = bottomInset),
        ) {
            // Outside the scrolling region below, so it stays put and stays grabbable
            dragHandle?.let {
                Box(modifier = handleModifier.fillMaxWidth()) { it() }
            }

            val sheetScrolls = contentScroll == SheetContentScroll.SheetOwned && maxHeight.isSpecified
            Box(
                modifier = if (sheetScrolls) {
                    Modifier
                        .weight(weight = 1f, fill = false)
                        .verticalScroll(scrollState)
                } else {
                    Modifier
                },
            ) {
                content()
            }
        }
    }
}

/**
 * Vertical displacement of the sheet card, driven by the drag handle and by content scrolls that
 * reach the sheet through [SheetNestedScrollConnection].
 */
@Stable
private class SheetDragState(
    private val dismissThresholdPx: Float,
    private val velocityThreshold: Float,
) {
    var offset by mutableFloatStateOf(0f)
        private set

    /** Down is positive; the sheet never travels above its resting place. */
    fun dragBy(delta: Float) {
        offset = (offset + delta).coerceAtLeast(0f)
    }

    fun snapToRest() {
        offset = 0f
    }

    /** Returns whether the gesture ended far or fast enough to dismiss the sheet. */
    suspend fun settle(velocity: Float): Boolean {
        if (offset > dismissThresholdPx || velocity > velocityThreshold) return true
        animate(
            initialValue = offset,
            targetValue = 0f,
            initialVelocity = velocity,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        ) { value, _ -> offset = value }
        return false
    }
}

/**
 * Hands vertical gestures back and forth between the content and the sheet.
 *
 * A plain `draggable` on the card cannot do this: it has no idea whether the content underneath the
 * finger still has somewhere to scroll, so every downward drag would either dismiss the sheet or be
 * swallowed. A real nested-scroll parent sees what the child could not use.
 *
 * A fling never dismisses: only [NestedScrollSource.UserInput] moves the sheet, and leftover fling
 * velocity is swallowed, so a fling that reaches the top of the content stops there — matching
 * Material's sheets and avoiding dismissals the user did not aim for.
 */
private class SheetNestedScrollConnection(
    private val dragState: SheetDragState,
    private val onDismiss: () -> Unit,
) : NestedScrollConnection {

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        // Upward while the sheet is displaced: put the sheet back before the content moves at all
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        if (available.y >= 0f || dragState.offset <= 0f) return Offset.Zero
        val consumed = -min(-available.y, dragState.offset)
        dragState.dragBy(consumed)
        return Offset(0f, consumed)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        // Downward the content could not use, because it is already at its top: the sheet takes it
        if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
        dragState.dragBy(available.y)
        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (dragState.offset <= 0f) return Velocity.Zero
        if (dragState.settle(available.y)) onDismiss()
        return available
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

@Composable
private fun DefaultDragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(width = 32.dp, height = 4.dp),
            shape = RoundedCornerShape(2.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        ) {}
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PaneScopedBottomSheetPreview() {
    PaneScopedBottomSheet(
        visible = true,
        onDismiss = {},
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Bottom Sheet Title",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "This is sample content for the pane-scoped bottom sheet.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PaneScopedBottomSheetNoDragHandlePreview() {
    PaneScopedBottomSheet(
        visible = true,
        onDismiss = {},
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "No Drag Handle",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "This sheet has no drag handle but can still be dragged.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
