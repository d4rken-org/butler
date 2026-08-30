package eu.darken.butler.workspace.ui.floatingbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.common.WorkspacePaddings
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A composable that manages floating bars at the top or bottom of the screen.
 *
 * Features:
 * - Automatic height measurement for each bar
 * - Reactive content padding based on visible bars (use [FloatingBarStackState.contentPaddingDp])
 * - Coordinated scroll behaviors (static, collapse, hide)
 * - Gap-filling animations when bars show/hide
 * - Bars render in declared top-to-bottom visual order, for both positions
 *
 * Content should be rendered separately, using [FloatingBarStackState.nestedScrollConnection]
 * for scroll coordination and [contentPaddingDp] for padding.
 *
 * @param modifier Modifier for the root container.
 * @param position Whether bars are positioned at TOP or BOTTOM of the screen.
 * @param defaultSpacing Default spacing between bars.
 * @param edgePadding Padding from the screen edge to the first bar.
 * @param horizontalPadding Inset from the pane edges applied to every bar in this stack.
 * @param state State holder managing bar coordination. Use [rememberFloatingBarStackState].
 * @param bars Lambda to declare bars using [FloatingBarScope.FloatingBar].
 */
@Composable
fun FloatingBarStack(
    modifier: Modifier = Modifier,
    position: BarPosition,
    defaultSpacing: Dp = 8.dp,
    edgePadding: Dp = 8.dp,
    horizontalPadding: Dp = WorkspacePaddings.BarHorizontal,
    state: FloatingBarStackState = rememberFloatingBarStackState(position, defaultSpacing, edgePadding),
    bars: @Composable FloatingBarScope.() -> Unit,
) {
    val density = LocalDensity.current
    val scope = remember(state, horizontalPadding) { FloatingBarScopeImpl(state, horizontalPadding) }

    SubcomposeLayout(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(state.nestedScrollConnection),
    ) { constraints ->
        // Clear bar entries at start of each layout pass (not outside SubcomposeLayout,
        // since the layout lambda runs multiple times per composition during animations)
        scope.barEntries.clear()

        // Phase 1: Compose and measure bars to collect entries
        val barMeasurables = subcompose("bars") {
            scope.bars()
        }

        // Phase 2: Measure each bar
        val barPlaceables = barMeasurables.map { measurable ->
            measurable.measure(
                Constraints(
                    maxWidth = constraints.maxWidth,
                    maxHeight = constraints.maxHeight,
                )
            )
        }

        // Phase 3: Update measured heights and calculate positions
        scope.barEntries.forEachIndexed { index, barState ->
            val placeable = barPlaceables.getOrNull(index)
            if (placeable != null) {
                barState.measuredHeight = placeable.height.toFloat()
            }
        }

        layout(constraints.maxWidth, constraints.maxHeight) {
            // Place bars at their calculated positions
            // Use state.barStates directly since scope.barEntries may not be populated yet
            state.barStates.forEachIndexed { index, barState ->
                val placeable = barPlaceables.getOrNull(index) ?: return@forEachIndexed

                // Skip placing completely hidden bars
                if (barState.visibilityFraction <= 0f && !barState.visible) {
                    return@forEachIndexed
                }

                // Skip scroll-collapsed bars so they don't receive touches on their invisible area.
                // Required for VanishOnScroll (doesn't translate off-screen); also covers
                // HideOnScroll at fraction=1 as a layout-cost optimization.
                if (barState.isHitHiddenByScroll) {
                    return@forEachIndexed
                }

                val offset = state.getBarOffset(index)

                // Edge bar is the one closest to screen edge (prevents peek during hide bounce-back)
                // Bars are declared in visual top-to-bottom order:
                // - TOP: index 0 is at top edge
                // - BOTTOM: lastIndex is at bottom edge
                val isEdgeBar = when (position) {
                    BarPosition.TOP -> index == 0
                    BarPosition.BOTTOM -> index == state.barStates.lastIndex
                }

                // Calculate scroll-based translation for HideOnScroll bars
                // For BOTTOM: translate down (positive) to hide below screen
                // For TOP: translate up (negative) to hide above screen
                val scrollTranslation = when (barState.scrollBehavior) {
                    is BarScrollBehavior.HideOnScroll -> {
                        // Move by offset + height to go fully off-screen
                        // Edge bars use edgeClampedFraction to prevent peek during hide bounce-back
                        // Inner bars bounce freely
                        val fraction = if (isEdgeBar) {
                            barState.edgeClampedFraction
                        } else {
                            barState.scrollCollapsedFraction
                        }
                        (offset + placeable.height) * fraction
                    }
                    // CollapseOnScroll: bar stays in place, content collapses via scrollCollapsedFraction
                    // Static/VanishOnScroll: no translation
                    is BarScrollBehavior.CollapseOnScroll,
                    is BarScrollBehavior.Static,
                    is BarScrollBehavior.VanishOnScroll -> 0f
                }

                // Calculate alpha and scale for VanishOnScroll bars (pop effect)
                // Use edgeClampedFraction to prevent ghostly peek during bounce-back
                val (alpha, scale) = when (barState.scrollBehavior) {
                    is BarScrollBehavior.VanishOnScroll -> {
                        val fraction = barState.edgeClampedFraction
                        val popAlpha = 1f - fraction
                        // Scale from 1.0 down to 0.85 for a subtle pop effect
                        val popScale = 1f - (fraction * 0.15f)
                        popAlpha to popScale
                    }
                    else -> 1f to 1f
                }

                val y = when (position) {
                    BarPosition.TOP -> offset - scrollTranslation
                    BarPosition.BOTTOM -> constraints.maxHeight - offset - placeable.height + scrollTranslation
                }

                placeable.placeRelativeWithLayer(
                    x = 0,
                    y = y.roundToInt(),
                    zIndex = (state.barStates.size - index).toFloat(), // Edge bars on top
                ) {
                    this.alpha = alpha
                    this.scaleX = scale
                    this.scaleY = scale
                }
            }
        }
    }
}

/**
 * Internal implementation of [FloatingBarScope].
 */
@Stable
internal class FloatingBarScopeImpl(
    private val stackState: FloatingBarStackState,
    private val horizontalPadding: Dp,
) : FloatingBarScope() {

    internal val barEntries = mutableStateListOf<FloatingBarState>()

    @Composable
    override fun FloatingBarImpl(
        modifier: Modifier,
        key: String,
        visible: Boolean,
        scrollBehavior: BarScrollBehavior,
        animation: BarAnimation,
        estimatedHeight: Dp,
        revealOn: Any?,
        content: @Composable FloatingBarContentScope.() -> Unit,
    ) {
        val density = LocalDensity.current
        val estimatedHeightPx = with(density) { estimatedHeight.toPx() }
        val coroutineScope = rememberCoroutineScope()

        // Create or restore bar state - register immediately during remember. Keyed on the caller's
        // stable key: the bar's identity has to survive a new composition, which is exactly when its
        // collapse state is restored.
        val barState = remember(key) {
            FloatingBarState(
                id = key,
                scrollBehavior = scrollBehavior,
                animation = animation,
                initialVisible = visible,
                estimatedHeightPx = estimatedHeightPx,
            ).also {
                // Register immediately so it's available during layout
                stackState.registerBar(it)
            }
        }

        // Track entry for local iteration
        barEntries.add(barState)

        // Cleanup on dispose
        DisposableEffect(barState.id) {
            onDispose {
                stackState.unregisterBar(barState.id)
            }
        }

        // Handle visibility changes
        LaunchedEffect(visible) {
            if (barState.visible != visible) {
                // When becoming visible again, cancel any latent scroll-collapse so the bar
                // doesn't re-appear off-screen / collapsed.
                if (visible && barState.scrollBehavior !is BarScrollBehavior.Static) {
                    barState.scrollCollapseAnimatable.snapTo(0f)
                }
                when (animation) {
                    is BarAnimation.Immediate -> barState.setVisibilityImmediate(visible)
                    else -> coroutineScope.launch {
                        barState.animateVisibility(visible, animation.toAnimationSpec())
                    }
                }
            }
        }

        // Update scroll behavior synchronously so scroll handler sees the correct value immediately
        SideEffect {
            barState.scrollBehavior = scrollBehavior
        }

        // Animate back to visible when switching to Static
        LaunchedEffect(scrollBehavior) {
            if (scrollBehavior is BarScrollBehavior.Static) {
                barState.triggerScrollCollapse(coroutineScope, 0f)
            }
        }

        // Caller-driven scroll-collapse reset: when revealOn's value changes, snap scroll to 0
        // so a scroll-hidden bar re-appears on a meaningful content/mode change.
        // Skipped on initial composition (handled by the barState's initial scrollCollapsedFraction=0).
        if (revealOn != null) {
            LaunchedEffect(revealOn) {
                if (barState.scrollBehavior !is BarScrollBehavior.Static &&
                    barState.scrollCollapsedFraction > 0f) {
                    barState.scrollCollapseAnimatable.snapTo(0f)
                }
            }
        }

        // Create content scope with current collapsed fraction
        val contentScope = FloatingBarContentScope(
            collapsedFraction = barState.scrollCollapsedFraction,
        )

        // Render bar content wrapped in measurement container
        Box(
            modifier = Modifier
                .padding(horizontal = horizontalPadding)
                .then(modifier)
                .onGloballyPositioned { coords ->
                    barState.measuredHeight = coords.size.height.toFloat()
                },
            contentAlignment = when (stackState.position) {
                BarPosition.TOP -> Alignment.TopCenter
                BarPosition.BOTTOM -> Alignment.BottomCenter
            },
        ) {
            contentScope.content()
        }
    }
}

// region Previews

@Composable
private fun PreviewBar(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FloatingBarStackBottomPreview() {
    val barStackState = rememberFloatingBarStackState(
        position = BarPosition.BOTTOM,
    )
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(barStackState.nestedScrollConnection),
            contentPadding = PaddingValues(bottom = barStackState.contentPaddingDp()),
        ) {
            items(20) { index ->
                Text(
                    text = "Item $index",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                )
            }
        }
        FloatingBarStack(
            modifier = Modifier.align(Alignment.BottomCenter),
            state = barStackState,
            position = BarPosition.BOTTOM,
            bars = {
                FloatingBar(
                    key = "bottom-1",
                    scrollBehavior = BarScrollBehavior.VanishOnScroll,
                ) {
                    PreviewBar("VanishOnScroll", MaterialTheme.colorScheme.tertiaryContainer)
                }
                FloatingBar(
                    key = "bottom-2",
                    scrollBehavior = BarScrollBehavior.VanishOnScroll,
                ) {
                    PreviewBar("VanishOnScroll", MaterialTheme.colorScheme.secondaryContainer)
                }
                FloatingBar(
                    key = "bottom-edge",
                    scrollBehavior = BarScrollBehavior.HideOnScroll,
                ) {
                    PreviewBar("HideOnScroll (edge)", MaterialTheme.colorScheme.primaryContainer)
                }
            },
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FloatingBarStackTopPreview() {
    val barStackState = rememberFloatingBarStackState(
        position = BarPosition.TOP,
    )
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(barStackState.nestedScrollConnection),
            contentPadding = PaddingValues(top = barStackState.contentPaddingDp()),
        ) {
            items(20) { index ->
                Text(
                    text = "Item $index",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                )
            }
        }
        FloatingBarStack(
            modifier = Modifier.align(Alignment.TopCenter),
            state = barStackState,
            position = BarPosition.TOP,
            bars = {
                FloatingBar(
                    key = "top-edge",
                    scrollBehavior = BarScrollBehavior.VanishOnScroll,
                ) {
                    PreviewBar("Toolbar (edge)", MaterialTheme.colorScheme.primaryContainer)
                }
                FloatingBar(
                    key = "top-static",
                    scrollBehavior = BarScrollBehavior.Static,
                ) {
                    PreviewBar("Static bar", MaterialTheme.colorScheme.surfaceVariant)
                }
            },
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FloatingBarStackMixedVisibilityPreview() {
    val barStackState = rememberFloatingBarStackState(
        position = BarPosition.BOTTOM,
    )
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(barStackState.nestedScrollConnection),
            contentPadding = PaddingValues(bottom = barStackState.contentPaddingDp()),
        ) {
            items(20) { index ->
                Text(
                    text = "Item $index",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                )
            }
        }
        FloatingBarStack(
            modifier = Modifier.align(Alignment.BottomCenter),
            state = barStackState,
            position = BarPosition.BOTTOM,
            bars = {
                FloatingBar(
                    key = "mixed-1",
                    visible = true,
                ) {
                    PreviewBar("Visible bar 1", MaterialTheme.colorScheme.tertiaryContainer)
                }
                FloatingBar(
                    key = "mixed-hidden",
                    visible = false, // Hidden - gap should be filled
                ) {
                    PreviewBar("Hidden bar", MaterialTheme.colorScheme.errorContainer)
                }
                FloatingBar(
                    key = "mixed-2",
                    visible = true,
                ) {
                    PreviewBar("Visible bar 2", MaterialTheme.colorScheme.primaryContainer)
                }
            },
        )
    }
}

// endregion
