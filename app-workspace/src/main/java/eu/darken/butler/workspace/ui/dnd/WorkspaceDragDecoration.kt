package eu.darken.butler.workspace.ui.dnd

import android.content.res.Resources
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Layers
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.contracts.dnd.WorkspaceDragPayload
import eu.darken.butler.common.R as CommonR

internal val STACK_OFFSET = 6.dp
internal val CARD_CORNER_DEFAULT = 8.dp
internal val SUMMARY_MAX_WIDTH = 280.dp
internal val SUMMARY_PADDING = 16.dp
internal val SUMMARY_PADDING_VERTICAL = 12.dp
internal val SUMMARY_ICON = 24.dp
internal val SUMMARY_GAP = 12.dp

private val PLATE_BORDER = 1.dp
private const val STACK_MAX_ITEMS = 3

/** What the drag shadow shows: the dragged item itself, a stack of cards, or a counted summary. */
internal sealed interface DragDecorationSpec {
    data object Single : DragDecorationSpec
    data class Stack(val depth: Int) : DragDecorationSpec
    data class Summary(val total: Int, val folders: Int, val files: Int) : DragDecorationSpec
}

internal fun dragDecorationSpec(items: List<WorkspaceDragPayload.Item>): DragDecorationSpec? = when {
    items.isEmpty() -> null
    items.size == 1 -> DragDecorationSpec.Single
    items.size <= STACK_MAX_ITEMS -> DragDecorationSpec.Stack(items.size)
    else -> {
        val folders = items.count { it.kind == WorkspaceDragPayload.Kind.DIRECTORY }
        DragDecorationSpec.Summary(
            total = items.size,
            folders = folders,
            files = items.size - folders,
        )
    }
}

internal data class DecorationLayout(
    val size: Size,
    /** Back to front, so the last one is the card the dragged content goes on. */
    val plates: List<Rect>,
    val iconBounds: Rect?,
    val countOffset: Offset?,
    val breakdownOffset: Offset?,
)

internal fun decorationLayout(
    spec: DragDecorationSpec,
    sourceSize: Size,
    density: Density,
    layoutDirection: LayoutDirection,
    countSize: IntSize,
    breakdownSize: IntSize?,
): DecorationLayout {
    val isRtl = layoutDirection == LayoutDirection.Rtl
    return when (spec) {
        is DragDecorationSpec.Single -> DecorationLayout(
            size = sourceSize,
            plates = listOf(Rect(Offset.Zero, sourceSize)),
            iconBounds = null,
            countOffset = null,
            breakdownOffset = null,
        )

        is DragDecorationSpec.Stack -> {
            val step = with(density) { STACK_OFFSET.toPx() }
            val span = step * (spec.depth - 1)
            val size = Size(sourceSize.width + span, sourceSize.height + span)
            // The front card sits at the top, the ones behind it fan out toward the end edge.
            val plates = (spec.depth - 1 downTo 0).map { depth ->
                val left = if (isRtl) span - depth * step else depth * step
                Rect(Offset(left, depth * step), sourceSize)
            }
            DecorationLayout(
                size = size,
                plates = plates,
                iconBounds = null,
                countOffset = null,
                breakdownOffset = null,
            )
        }

        is DragDecorationSpec.Summary -> {
            val padding = with(density) { SUMMARY_PADDING.toPx() }
            val paddingVertical = with(density) { SUMMARY_PADDING_VERTICAL.toPx() }
            val icon = with(density) { SUMMARY_ICON.toPx() }
            val gap = with(density) { SUMMARY_GAP.toPx() }
            val maxWidth = with(density) { SUMMARY_MAX_WIDTH.toPx() }
            val textWidth = maxOf(countSize.width, breakdownSize?.width ?: 0).toFloat()
            val textHeight = (countSize.height + (breakdownSize?.height ?: 0)).toFloat()
            val width = (padding * 2 + icon + gap + textWidth).coerceAtMost(maxWidth)
            val height = paddingVertical * 2 + maxOf(icon, textHeight)
            val iconLeft = if (isRtl) width - padding - icon else padding
            val textEdge = if (isRtl) width - padding - icon - gap else padding + icon + gap
            val countLeft = if (isRtl) textEdge - countSize.width else textEdge
            val breakdownLeft = if (isRtl) textEdge - (breakdownSize?.width ?: 0) else textEdge
            val textTop = (height - textHeight) / 2
            DecorationLayout(
                size = Size(width, height),
                plates = listOf(Rect(Offset.Zero, Size(width, height))),
                iconBounds = Rect(Offset(iconLeft, (height - icon) / 2), Size(icon, icon)),
                countOffset = Offset(countLeft, textTop),
                breakdownOffset = breakdownSize?.let { Offset(breakdownLeft, textTop + countSize.height) },
            )
        }
    }
}

internal data class SummaryLabels(val count: String, val breakdown: String)

/**
 * Draws the drag shadow. Built in composition so theme, density and resources are available, but
 * invoked later from the drag source's modifier node, on a canvas the platform owns.
 */
internal class WorkspaceDragDecoration(
    private val textMeasurer: TextMeasurer,
    private val density: Density,
    private val layoutDirection: LayoutDirection,
    private val countStyle: TextStyle,
    private val breakdownStyle: TextStyle,
    private val containerColor: Color,
    private val outlineColor: Color,
    private val icon: Painter,
    private val iconTint: Color,
    private val labels: (DragDecorationSpec.Summary) -> SummaryLabels,
) {

    private class SummaryText(val count: TextLayoutResult, val breakdown: TextLayoutResult)

    fun decorationSize(spec: DragDecorationSpec, sourceSize: Size, cornerRadius: Dp): Size =
        layout(spec, sourceSize, summaryText(spec)).size

    fun draw(scope: DrawScope, spec: DragDecorationSpec, cornerRadius: Dp, recorded: GraphicsLayer?) = with(scope) {
        val text = summaryText(spec)
        val layout = layout(spec, sourceSize(spec, size), text)
        val radius = CornerRadius(with(density) { cornerRadius.toPx() })
        val border = Stroke(width = with(density) { PLATE_BORDER.toPx() })
        val content = recorded?.takeIf { spec !is DragDecorationSpec.Summary }

        layout.plates.forEachIndexed { index, plate ->
            drawRoundRect(
                color = containerColor,
                topLeft = plate.topLeft,
                size = plate.size,
                cornerRadius = radius,
            )
            if (content != null && index == layout.plates.lastIndex) {
                val clip = Path().apply { addRoundRect(RoundRect(plate, radius)) }
                clipPath(clip) {
                    translate(plate.left, plate.top) { drawLayer(content) }
                }
            }
            drawRoundRect(
                color = outlineColor,
                topLeft = plate.topLeft,
                size = plate.size,
                cornerRadius = radius,
                style = border,
            )
        }

        if (text != null) {
            layout.iconBounds?.let { bounds ->
                translate(bounds.left, bounds.top) {
                    with(icon) { draw(size = bounds.size, colorFilter = ColorFilter.tint(iconTint)) }
                }
            }
            layout.countOffset?.let { drawText(text.count, topLeft = it) }
            layout.breakdownOffset?.let { drawText(text.breakdown, topLeft = it) }
        }
    }

    private fun layout(spec: DragDecorationSpec, sourceSize: Size, text: SummaryText?) = decorationLayout(
        spec = spec,
        sourceSize = sourceSize,
        density = density,
        layoutDirection = layoutDirection,
        countSize = text?.count?.size ?: IntSize.Zero,
        breakdownSize = text?.breakdown?.size,
    )

    /** The canvas is sized for the whole decoration, so the stack fan has to be taken back off. */
    private fun sourceSize(spec: DragDecorationSpec, decorationSize: Size): Size {
        if (spec !is DragDecorationSpec.Stack) return decorationSize
        val span = with(density) { STACK_OFFSET.toPx() } * (spec.depth - 1)
        return Size(decorationSize.width - span, decorationSize.height - span)
    }

    private fun summaryText(spec: DragDecorationSpec): SummaryText? {
        if (spec !is DragDecorationSpec.Summary) return null
        val labels = labels(spec)
        return SummaryText(
            count = measureLine(labels.count, countStyle),
            breakdown = measureLine(labels.breakdown, breakdownStyle),
        )
    }

    /** Measured against the same budget it is drawn in, so an overlong line ellipsises instead of clipping. */
    private fun measureLine(text: String, style: TextStyle): TextLayoutResult = textMeasurer.measure(
        text = text,
        style = style,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        constraints = Constraints(maxWidth = textBudget),
        layoutDirection = layoutDirection,
        density = density,
    )

    private val textBudget: Int
        get() = with(density) {
            (SUMMARY_MAX_WIDTH - SUMMARY_PADDING * 2 - SUMMARY_ICON - SUMMARY_GAP).roundToPx()
        }
}

@Composable
internal fun rememberWorkspaceDragDecoration(): WorkspaceDragDecoration {
    val textMeasurer = rememberTextMeasurer()
    val icon = rememberVectorPainter(Icons.TwoTone.Layers)
    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val configuration = LocalConfiguration.current
    val resources = LocalContext.current.resources
    return remember(colorScheme, typography, density, layoutDirection, configuration) {
        WorkspaceDragDecoration(
            textMeasurer = textMeasurer,
            density = density,
            layoutDirection = layoutDirection,
            countStyle = typography.titleSmall.copy(color = colorScheme.onSurface),
            breakdownStyle = typography.bodySmall.copy(color = colorScheme.onSurfaceVariant),
            containerColor = colorScheme.surfaceContainerHighest,
            outlineColor = colorScheme.outlineVariant,
            icon = icon,
            iconTint = colorScheme.primary,
            labels = { spec -> summaryLabels(resources, spec) },
        )
    }
}

private fun summaryLabels(resources: Resources, spec: DragDecorationSpec.Summary): SummaryLabels {
    val folders = resources.getQuantityString(CommonR.plurals.common_folders_count, spec.folders, spec.folders)
    val files = resources.getQuantityString(CommonR.plurals.common_files_count, spec.files, spec.files)
    return SummaryLabels(
        count = resources.getQuantityString(R.plurals.workspace_drag_decoration_items, spec.total, spec.total),
        breakdown = when {
            spec.folders > 0 && spec.files > 0 -> {
                resources.getString(R.string.workspace_drag_decoration_breakdown, folders, files)
            }
            spec.files > 0 -> files
            else -> folders
        },
    )
}

@Composable
private fun DragDecorationCanvas(
    modifier: Modifier = Modifier,
    spec: DragDecorationSpec,
    sourceSize: DpSize = DpSize(220.dp, 56.dp),
    cornerRadius: Dp = CARD_CORNER_DEFAULT,
) {
    val decoration = rememberWorkspaceDragDecoration()
    val density = LocalDensity.current
    val size = with(density) {
        decoration.decorationSize(spec, sourceSize.toSize(), cornerRadius).toDpSize()
    }
    Canvas(modifier = modifier.size(size)) {
        decoration.draw(this, spec, cornerRadius, null)
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun DragDecorationStackPreview() {
    PreviewWrapper {
        DragDecorationCanvas(
            modifier = Modifier.padding(16.dp),
            spec = DragDecorationSpec.Stack(depth = 3),
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun DragDecorationSummaryMixedPreview() {
    PreviewWrapper {
        DragDecorationCanvas(
            modifier = Modifier.padding(16.dp),
            spec = DragDecorationSpec.Summary(total = 12, folders = 4, files = 8),
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun DragDecorationSummaryFoldersPreview() {
    PreviewWrapper {
        DragDecorationCanvas(
            modifier = Modifier.padding(16.dp),
            spec = DragDecorationSpec.Summary(total = 5, folders = 5, files = 0),
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun DragDecorationSummaryRtlPreview() {
    PreviewWrapper {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            DragDecorationCanvas(
                modifier = Modifier.padding(16.dp),
                spec = DragDecorationSpec.Summary(total = 12, folders = 4, files = 8),
            )
        }
    }
}
