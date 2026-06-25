package eu.darken.butler.workspace.ui.bottomsheet

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    dragHandle: @Composable (() -> Unit)? = { DefaultDragHandle() },
    content: @Composable () -> Unit,
) {
    // In preview mode, just show the content as a card
    if (LocalInspectionMode.current) {
        if (!visible) return
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .then(if (includeImePadding) Modifier.imePadding() else Modifier)
                    .padding(bottom = bottomInset),
            ) {
                dragHandle?.invoke()
                content()
            }
        }
        return
    }

    val density = LocalDensity.current
    val dismissThreshold = with(density) { 100.dp.toPx() }
    val velocityThreshold = 1000f

    val scope = rememberCoroutineScope()
    val dragOffset = remember { Animatable(0f) }

    // Reset offset when becoming visible
    LaunchedEffect(visible) {
        if (visible) {
            dragOffset.snapTo(0f)
        }
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

    BackHandler(enabled = visible, onBack = onDismiss)

    Box(modifier = Modifier.fillMaxSize()) {
        // Scrim overlay (pane-local, not full-screen)
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(200)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )
        }

        // Bottom sheet content
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topInset),
            contentAlignment = Alignment.BottomCenter
        ) {
            AnimatedVisibility(
                visible = visible,
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
                Card(
                    modifier = modifier
                        .fillMaxWidth()
                        .offset { IntOffset(0, dragOffset.value.roundToInt()) }
                        .draggable(
                            state = rememberDraggableState { delta ->
                                scope.launch {
                                    val newOffset = (dragOffset.value + delta).coerceAtLeast(0f)
                                    dragOffset.snapTo(newOffset)
                                }
                            },
                            orientation = Orientation.Vertical,
                            onDragStopped = { velocity ->
                                scope.launch {
                                    if (dragOffset.value > dismissThreshold || velocity > velocityThreshold) {
                                        onDismiss()
                                    } else {
                                        dragOffset.animateTo(
                                            targetValue = 0f,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessMedium
                                            )
                                        )
                                    }
                                }
                            }
                        ),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .then(if (includeImePadding) Modifier.imePadding() else Modifier)
                            .padding(bottom = bottomInset),
                    ) {
                        dragHandle?.invoke()
                        content()
                    }
                }
            }
        }
    }
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
