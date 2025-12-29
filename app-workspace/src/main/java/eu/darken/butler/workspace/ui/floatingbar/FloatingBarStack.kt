package eu.darken.butler.workspace.ui.floatingbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

/**
 * A scaffold-like composable that manages floating bars at the top or bottom of the screen.
 *
 * Features:
 * - Automatic height measurement for each bar
 * - Reactive content padding based on visible bars
 * - Coordinated scroll behaviors (static, collapse, hide)
 * - Gap-filling animations when bars show/hide
 * - Edge-first ordering (first bar closest to screen edge)
 *
 * @param modifier Modifier for the root container.
 * @param position Whether bars are positioned at TOP or BOTTOM of the screen.
 * @param defaultSpacing Default spacing between bars.
 * @param edgePadding Padding from the screen edge to the first bar.
 * @param state State holder managing bar coordination. Use [rememberFloatingBarStackState].
 * @param bars Lambda to declare bars using [FloatingBarScope.FloatingBar].
 * @param content Main content that receives [PaddingValues] accounting for all visible bars.
 */
@Composable
fun FloatingBarStack(
    modifier: Modifier = Modifier,
    position: BarPosition,
    defaultSpacing: Dp = 8.dp,
    edgePadding: Dp = 8.dp,
    state: FloatingBarStackState = rememberFloatingBarStackState(position, defaultSpacing, edgePadding),
    bars: @Composable FloatingBarScope.() -> Unit,
    content: @Composable (contentPadding: PaddingValues) -> Unit,
) {
    val density = LocalDensity.current
    val scope = remember(state) { FloatingBarScopeImpl(state) }

    // Clear previous bar entries on recomposition
    scope.barEntries.clear()

    SubcomposeLayout(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(state.nestedScrollConnection),
    ) { constraints ->
        // Phase 1: Compose and measure bars to collect entries
        val barMeasurables = subcompose("bars") {
            scope.bars()
        }

        // Phase 2: Calculate content padding (clamp to non-negative for bounce animations)
        val contentPaddingPx = state.contentPaddingPx.coerceAtLeast(0f)
        val contentPadding = when (position) {
            BarPosition.TOP -> PaddingValues(top = with(density) { contentPaddingPx.toDp() })
            BarPosition.BOTTOM -> PaddingValues(bottom = with(density) { contentPaddingPx.toDp() })
        }

        // Phase 3: Compose and measure content with padding
        val contentPlaceable = subcompose("content") {
            content(contentPadding)
        }.firstOrNull()?.measure(constraints)

        // Phase 4: Measure each bar
        val barPlaceables = barMeasurables.map { measurable ->
            measurable.measure(
                Constraints(
                    maxWidth = constraints.maxWidth,
                    maxHeight = constraints.maxHeight,
                )
            )
        }

        // Phase 5: Update measured heights and calculate positions
        scope.barEntries.forEachIndexed { index, entry ->
            val placeable = barPlaceables.getOrNull(index)
            if (placeable != null) {
                entry.state.measuredHeight = placeable.height.toFloat()
            }
        }

        layout(constraints.maxWidth, constraints.maxHeight) {
            // Place content first (full size)
            contentPlaceable?.placeRelative(0, 0)

            // Place bars at their calculated positions
            // Use state.barStates directly since scope.barEntries may not be populated yet
            state.barStates.forEachIndexed { index, barState ->
                val placeable = barPlaceables.getOrNull(index) ?: return@forEachIndexed

                // Skip placing completely hidden bars
                if (barState.visibilityFraction <= 0f && !barState.visible) {
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
 * Internal entry tracking a bar and its state.
 */
internal data class BarEntry(
    val state: FloatingBarState,
    val collapsedHeightPx: Float,
)

/**
 * Internal implementation of [FloatingBarScope].
 */
@Stable
internal class FloatingBarScopeImpl(
    private val stackState: FloatingBarStackState,
) : FloatingBarScope() {

    internal val barEntries = mutableStateListOf<BarEntry>()

    @Composable
    override fun FloatingBarImpl(
        modifier: Modifier,
        visible: Boolean,
        scrollBehavior: BarScrollBehavior,
        animation: BarAnimation,
        collapsedHeight: Dp,
        estimatedHeight: Dp,
        content: @Composable FloatingBarContentScope.() -> Unit,
    ) {
        val density = LocalDensity.current
        val collapsedHeightPx = with(density) { collapsedHeight.toPx() }
        val estimatedHeightPx = with(density) { estimatedHeight.toPx() }
        val coroutineScope = rememberCoroutineScope()

        // Create or restore bar state - register immediately during remember
        val barState = remember {
            FloatingBarState(
                id = Uuid.random().toString(),
                scrollBehavior = scrollBehavior,
                animation = animation,
                initialVisible = visible,
                estimatedHeightPx = estimatedHeightPx,
            ).also {
                it.collapsedHeightPx = collapsedHeightPx
                // Register immediately so it's available during layout
                stackState.registerBar(it)
            }
        }

        // Track entry for local iteration
        barEntries.add(BarEntry(barState, collapsedHeightPx))

        // Cleanup on dispose
        DisposableEffect(barState.id) {
            onDispose {
                stackState.unregisterBar(barState.id)
            }
        }

        // Handle visibility changes
        LaunchedEffect(visible) {
            if (barState.visible != visible) {
                when (animation) {
                    is BarAnimation.Immediate -> barState.setVisibilityImmediate(visible)
                    else -> coroutineScope.launch {
                        barState.animateVisibility(visible, animation.toAnimationSpec())
                    }
                }
            }
        }

        // Update collapsed height if changed
        LaunchedEffect(collapsedHeightPx) {
            barState.collapsedHeightPx = collapsedHeightPx
        }

        // Update scroll behavior if changed (e.g., operations bar switching from VanishOnScroll to Static)
        LaunchedEffect(scrollBehavior) {
            barState.scrollBehavior = scrollBehavior
        }

        // Create content scope with current collapsed fraction
        val contentScope = FloatingBarContentScope(
            collapsedFraction = barState.scrollCollapsedFraction,
        )

        // Render bar content wrapped in measurement container
        Box(
            modifier = modifier.onGloballyPositioned { coords ->
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
@Composable
private fun FloatingBarStackBottomPreview() {
    PreviewWrapper {
        FloatingBarStack(
            position = BarPosition.BOTTOM,
            bars = {
                FloatingBar(
                    scrollBehavior = BarScrollBehavior.VanishOnScroll,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    PreviewBar("VanishOnScroll", MaterialTheme.colorScheme.tertiaryContainer)
                }
                FloatingBar(
                    scrollBehavior = BarScrollBehavior.VanishOnScroll,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    PreviewBar("VanishOnScroll", MaterialTheme.colorScheme.secondaryContainer)
                }
                FloatingBar(
                    scrollBehavior = BarScrollBehavior.HideOnScroll,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    PreviewBar("HideOnScroll (edge)", MaterialTheme.colorScheme.primaryContainer)
                }
            },
        ) { contentPadding ->
            LazyColumn(contentPadding = contentPadding) {
                items(20) { index ->
                    Text(
                        text = "Item $index",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    )
                }
            }
        }
    }
}

@Preview2
@Composable
private fun FloatingBarStackTopPreview() {
    PreviewWrapper {
        FloatingBarStack(
            position = BarPosition.TOP,
            bars = {
                FloatingBar(
                    scrollBehavior = BarScrollBehavior.VanishOnScroll,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    PreviewBar("Toolbar (edge)", MaterialTheme.colorScheme.primaryContainer)
                }
                FloatingBar(
                    scrollBehavior = BarScrollBehavior.Static,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    PreviewBar("Static bar", MaterialTheme.colorScheme.surfaceVariant)
                }
            },
        ) { contentPadding ->
            LazyColumn(contentPadding = contentPadding) {
                items(20) { index ->
                    Text(
                        text = "Item $index",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    )
                }
            }
        }
    }
}

@Preview2
@Composable
private fun FloatingBarStackMixedVisibilityPreview() {
    PreviewWrapper {
        FloatingBarStack(
            position = BarPosition.BOTTOM,
            bars = {
                FloatingBar(
                    visible = true,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    PreviewBar("Visible bar 1", MaterialTheme.colorScheme.tertiaryContainer)
                }
                FloatingBar(
                    visible = false, // Hidden - gap should be filled
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    PreviewBar("Hidden bar", MaterialTheme.colorScheme.errorContainer)
                }
                FloatingBar(
                    visible = true,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    PreviewBar("Visible bar 2", MaterialTheme.colorScheme.primaryContainer)
                }
            },
        ) { contentPadding ->
            LazyColumn(contentPadding = contentPadding) {
                items(20) { index ->
                    Text(
                        text = "Item $index",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    )
                }
            }
        }
    }
}

// endregion
