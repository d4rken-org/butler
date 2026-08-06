package eu.darken.butler.workspace.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

/**
 * Controls how [CutoutCard] renders the cutout area.
 */
enum class CutoutMode {
    /** Automatically choose based on content height: corner mode if content is tall enough, full-height otherwise. */
    Auto,

    /** Force corner cutout with notch shape in top-right corner. */
    Corner,

    /** Force full-height cutout with the card narrower and cutout content beside it. */
    FullHeight,
}

/**
 * A Card with an optional cutout that automatically sizes to fit the cutout content.
 *
 * The cutout content is measured first, and the card shape is adjusted to create a notch
 * where the cutout content is positioned.
 *
 * Two cutout modes are supported:
 * - **Corner cutout** (default): Top-right corner cutout with notch shape
 * - **Full-height cutout**: Right-edge cutout spanning full height, content is beside it
 *
 * Content is responsible for its own layout relative to the cutout. Use [CutoutAwareColumn] or
 * [CutoutAwareFlowRow] inside content if cutout-aware width constraints are needed.
 *
 * @param cutoutContent Composable to render in the cutout area. If null, renders a regular card.
 * @param cutoutMode Controls the cutout rendering mode. [CutoutMode.Auto] (default) selects based
 *                   on content height, [CutoutMode.Corner] forces notch shape, [CutoutMode.FullHeight]
 *                   forces the card-beside-button layout.
 * @param cutoutAlignment Vertical alignment of the cutout content next to the card. Only applies in
 *                        full-height mode, corner mode always pins the content into the top-right notch.
 * @param gapDistance Gap between cutout content and card content. In corner mode, this gap is
 *                    applied to both the left and bottom edges of the cutout. In full-height mode,
 *                    only the horizontal gap (left of cutout) is applied since the cutout spans
 *                    the full height.
 * @param contentPadding Padding inside the card for the main content. Use [CutoutCardDefaults.contentPadding]
 *                       factory functions to create custom padding values.
 * @param elevation Card elevation
 * @param colors Card colors
 * @param cornerRadius Corner radius for the card (and cutout inner corners)
 * @param content Main card content. Receives [CutoutCardScope] with cutout dimensions.
 */
@Composable
fun CutoutCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = CutoutCardDefaults.CornerRadius,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(defaultElevation = CutoutCardDefaults.ElevationDp),
    cutoutContent: (@Composable () -> Unit)? = null,
    cutoutMode: CutoutMode = CutoutMode.Auto,
    cutoutAlignment: Alignment.Vertical = Alignment.Top,
    contentPadding: PaddingValues = CutoutCardDefaults.contentPadding(),
    gapDistance: Dp = CutoutCardDefaults.GapDistanceExpanded,
    content: @Composable CutoutCardScope.() -> Unit,
) {
    if (cutoutContent == null) {
        // No cutout - render regular card with zero cutout dimensions.
        // BoxWithConstraints recovers an externally enforced min height (e.g. requiredHeightIn).
        // Card's Column measures its child with minHeight=0, so the min height has to be re-applied
        // inside, otherwise shorter content is top-aligned instead of centered.
        // propagateMinConstraints keeps the Card's own constraints identical to what the caller set.
        val scope = CutoutCardScopeImpl(cutoutWidth = 0.dp, cutoutHeight = 0.dp)
        BoxWithConstraints(modifier = modifier, propagateMinConstraints = true) {
            val cardMinHeight = minHeight
            Card(
                elevation = elevation,
                shape = RoundedCornerShape(cornerRadius),
                colors = colors,
            ) {
                Box(
                    modifier = Modifier.heightIn(min = cardMinHeight),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Column(modifier = Modifier.padding(contentPadding)) {
                        scope.content()
                    }
                }
            }
        }
        return
    }

    val density = LocalDensity.current
    val gapDistancePx = with(density) { gapDistance.roundToPx() }
    with(density) { cornerRadius.roundToPx() }

    SubcomposeLayout(modifier = modifier) { constraints ->
        // Phase 1: Measure cutout content to determine cutout size
        val cutoutPlaceable = subcompose("cutout") {
            cutoutContent()
        }.firstOrNull()?.measure(Constraints())

        val cutoutContentWidth = cutoutPlaceable?.width ?: 0
        val cutoutContentHeight = cutoutPlaceable?.height ?: 0

        // Calculate cutout dimensions
        val cutoutWidth = cutoutContentWidth + gapDistancePx
        val cutoutWidthDp = with(density) { cutoutWidth.toDp() }
        val cutoutHeight = cutoutContentHeight + gapDistancePx
        val cutoutHeightDp = with(density) { cutoutHeight.toDp() }

        // Create scope with calculated dimensions
        val scope = CutoutCardScopeImpl(cutoutWidth = cutoutWidthDp, cutoutHeight = cutoutHeightDp)

        // Calculate minimum height for card - should be at least as tall as cutout content
        val cardMinHeightPx = maxOf(constraints.minHeight, cutoutContentHeight)
        val cardMinHeightDp = with(density) { cardMinHeightPx.toDp() }

        val useFullHeightMode = when (cutoutMode) {
            CutoutMode.FullHeight -> true
            CutoutMode.Corner -> false
            // Only Auto has to measure the content as it would appear in full-height mode, to see
            // whether it extends enough below the cutout to justify corner mode.
            CutoutMode.Auto -> {
                val fullHeightCardWidth = (constraints.maxWidth - cutoutWidth).coerceAtLeast(0)
                val fullHeightScope = CutoutCardScopeImpl(cutoutWidth = 0.dp, cutoutHeight = 0.dp)
                val contentMeasurePlaceable = subcompose("content-measure") {
                    Column(modifier = Modifier.padding(contentPadding)) {
                        fullHeightScope.content()
                    }
                }.first().measure(constraints.copy(minWidth = 0, maxWidth = fullHeightCardWidth))

                val minHeightForCornerMode = cutoutHeight * 2
                contentMeasurePlaceable.height < minHeightForCornerMode
            }
        }

        // In full-height mode the card width is already reduced by cutoutWidth,
        // so give content a zeroed-out scope to avoid double-penalizing row widths.
        val renderScope = if (useFullHeightMode) {
            CutoutCardScopeImpl(cutoutWidth = 0.dp, cutoutHeight = 0.dp)
        } else {
            scope
        }

        // Phase 3: Measure card with appropriate mode
        val cardConstraints = if (useFullHeightMode) {
            // Full-height mode: constrain card width to leave space for cutout
            val cardWidth = (constraints.maxWidth - cutoutWidth).coerceAtLeast(0)
            constraints.copy(minWidth = cardWidth, maxWidth = cardWidth)
        } else {
            // Corner mode: enforce minimum height for CutoutTopRightCornerShape geometry.
            // Shape needs: cutoutHeight (notch) + cornerRadius * 3 (transition arc + bottom corner)
            val cornerRadiusPx = with(density) { cornerRadius.roundToPx() }
            val cornerMinHeight = cutoutHeight + cornerRadiusPx * 3
            constraints.copy(minHeight = maxOf(constraints.minHeight, cornerMinHeight))
        }

        val cardPlaceable = subcompose("card") {
            if (useFullHeightMode) {
                // Full-height mode: simple rounded card, width constrained by layout
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = elevation,
                    shape = RoundedCornerShape(cornerRadius),
                    colors = colors,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = cardMinHeightDp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(modifier = Modifier.padding(contentPadding)) {
                            renderScope.content()
                        }
                    }
                }
            } else {
                // Corner mode: cutout in top-right corner
                Card(
                    elevation = elevation,
                    shape = CutoutTopRightCornerShape(
                        cutoutWidth = cutoutWidthDp,
                        cutoutHeight = cutoutHeightDp,
                        cornerRadius = cornerRadius,
                        cutoutCornerRadius = cornerRadius,
                    ),
                    colors = colors,
                ) {
                    Column(modifier = Modifier.padding(contentPadding)) {
                        renderScope.content()
                    }
                }
            }
        }.first().measure(cardConstraints)

        // Total width includes card + cutout area
        val totalWidth = if (useFullHeightMode) constraints.maxWidth else cardPlaceable.width

        layout(totalWidth, cardPlaceable.height) {
            // Place the card
            cardPlaceable.placeRelative(0, 0)

            // Place cutout content - corner mode stays pinned to the top, because
            // CutoutTopRightCornerShape draws its notch there and content drifting out of the
            // notch would render on top of the card.
            cutoutPlaceable?.placeRelative(
                x = totalWidth - cutoutContentWidth,
                y = if (useFullHeightMode) cutoutAlignment.align(cutoutContentHeight, cardPlaceable.height) else 0,
            )
        }
    }
}

/**
 * Scope for [CutoutCard] content that provides access to cutout dimensions.
 *
 * Content can use [cutoutWidth] and [cutoutHeight] to implement cutout-aware layouts,
 * for example by passing them to [CutoutAwareFlowRow] or [CutoutAwareColumn].
 */
interface CutoutCardScope {
    /** Width of the cutout area (button + gap). 0.dp when no cutout exists. */
    val cutoutWidth: Dp

    /** Height of the cutout area (button + gap). 0.dp when no cutout exists. */
    val cutoutHeight: Dp
}

private class CutoutCardScopeImpl(
    override val cutoutWidth: Dp,
    override val cutoutHeight: Dp,
) : CutoutCardScope

/**
 * Default values for [CutoutCard].
 */
object CutoutCardDefaults {
    val CornerRadius = 12.dp
    val GapDistanceExpanded = 8.dp
    val GapDistanceCollapsed = 8.dp
    val ContentPaddingExpanded = 12.dp
    val ContentPaddingCollapsed = 6.dp
    val ElevationDp = 6.dp

    fun contentPadding(
        all: Dp = ContentPaddingExpanded,
    ): PaddingValues = PaddingValues(all)

    fun contentPadding(
        horizontal: Dp = ContentPaddingExpanded,
        vertical: Dp = ContentPaddingExpanded,
    ): PaddingValues = PaddingValues(horizontal, vertical)

    fun contentPadding(
        start: Dp = ContentPaddingExpanded,
        top: Dp = ContentPaddingExpanded,
        end: Dp = ContentPaddingExpanded,
        bottom: Dp = ContentPaddingExpanded,
    ): PaddingValues = PaddingValues(start, top, end, bottom)
}

@Composable
private fun PreviewCutoutButton() {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text("⊞", style = MaterialTheme.typography.titleLarge)
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun CutoutCardNoCutoutPreview() {
    CutoutCard(
        modifier = Modifier.padding(16.dp),
    ) {
        Text("Regular card without cutout")
        Text("Second line of content")
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun CutoutCardCornerModePreview() {
    CutoutCard(
        modifier = Modifier.padding(16.dp),
        cutoutContent = { PreviewCutoutButton() },
    ) {
        Text("Corner cutout mode")
        Text("Content flows around the cutout")
        Text("Third line extends full width")
        Text("Fourth line also full width")
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun CutoutCardFullHeightModePreview() {
    CutoutCard(
        modifier = Modifier.padding(16.dp),
        cutoutContent = { PreviewCutoutButton() },
        cutoutMode = CutoutMode.FullHeight,
    ) {
        Text("Full-height cutout mode")
        Text("Card width is reduced")
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun CutoutCardShortContentPreview() {
    CutoutCard(
        modifier = Modifier.padding(16.dp),
        cutoutContent = { PreviewCutoutButton() },
    ) {
        Text("Short content auto-switches to fullheight abc lorem ipsum Short content auto-switches to fullheight abc lorem ipsum")
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun CutoutCardNoCutoutRtlPreview() {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        CutoutCard(
            modifier = Modifier.padding(16.dp),
        ) {
            Text("Regular card without cutout RTL")
            Text("Second line of content")
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun CutoutCardCornerModeRtlPreview() {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        CutoutCard(
            modifier = Modifier.padding(16.dp),
            cutoutContent = { PreviewCutoutButton() },
        ) {
            Text("Corner cutout mode RTL")
            Text("Content flows around the cutout")
            Text("Third line extends full width")
            Text("Fourth line also full width")
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun CutoutCardFullHeightModeRtlPreview() {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        CutoutCard(
            modifier = Modifier.padding(16.dp),
            cutoutContent = { PreviewCutoutButton() },
            cutoutMode = CutoutMode.FullHeight,
        ) {
            Text("Full-height cutout mode RTL")
            Text("Card width is reduced")
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun CutoutCardShortContentRtlPreview() {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        CutoutCard(
            modifier = Modifier.padding(16.dp),
            cutoutContent = { PreviewCutoutButton() },
        ) {
            Text("Short content auto-switches to fullheight abc lorem ipsum Short content auto-switches to fullheight abc lorem ipsum")
        }
    }
}

