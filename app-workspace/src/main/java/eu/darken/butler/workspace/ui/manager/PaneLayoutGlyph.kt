package eu.darken.butler.workspace.ui.manager

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

object PaneLayoutGlyphDefaults {
    /**
     * Near-square rather than window-shaped. Width is capped by the notch the glyph sits in, height
     * is not, so a landscape aspect would spend the only free dimension on realism nobody can read
     * at this size - it left a quad grid's cells 3dp tall, which a 1dp stroke swallows.
     */
    val Width = 19.dp
    val Height = 19.dp
}

private val CellGutter = 1.dp
private val CellCorner = 1.dp
private val CellStroke = 1.dp

/**
 * A diagram of [layout] with the pane at [paneIndex] filled and the rest outlined - "this workspace
 * is in *that* part of the screen", which a pane number can only convey once the user has learned
 * how the layout numbers its panes.
 *
 * Inactive panes are outlined rather than drawn at low alpha: at this size a faint fill turns to
 * mush, while an outline keeps its edges.
 *
 * Purely decorative - it carries no semantics of its own. Callers name the pane on whatever node
 * already represents the thing the glyph belongs to, so that it does not become a second stop for
 * TalkBack.
 */
@Composable
fun PaneLayoutGlyph(
    modifier: Modifier = Modifier,
    layout: WorkspaceDesign.Layout,
    paneIndex: Int,
    width: Dp = PaneLayoutGlyphDefaults.Width,
    height: Dp = PaneLayoutGlyphDefaults.Height,
    activeColor: Color = MaterialTheme.colorScheme.tertiary,
    inactiveColor: Color = MaterialTheme.colorScheme.outline,
) {
    // PaneCell is layout-relative while the canvas draws in absolute coordinates, so RTL needs an
    // explicit flip here - the enclosing shape mirrors itself, but a canvas does not.
    val direction = LocalLayoutDirection.current
    val cells = remember(layout, direction) {
        paneCells(layout).map { it.toLeftRelative(direction) }
    }

    Canvas(modifier = modifier.size(width = width, height = height)) {
        val gutter = CellGutter.toPx()
        val stroke = CellStroke.toPx()
        val corner = CornerRadius(CellCorner.toPx())

        cells.forEachIndexed { index, cell ->
            val isActive = index == paneIndex
            // Half a gutter on every side: interior edges add up to a full gap, the outer edges
            // keep the diagram off the notch's own corners.
            val inset = gutter / 2f + if (isActive) 0f else stroke / 2f
            val topLeft = Offset(
                x = cell.startX * size.width + inset,
                y = cell.y * size.height + inset,
            )
            val cellSize = Size(
                width = (cell.width * size.width - inset * 2f).coerceAtLeast(0f),
                height = (cell.height * size.height - inset * 2f).coerceAtLeast(0f),
            )
            if (isActive) {
                drawRoundRect(
                    color = activeColor,
                    topLeft = topLeft,
                    size = cellSize,
                    cornerRadius = corner,
                )
            } else {
                drawRoundRect(
                    color = inactiveColor,
                    topLeft = topLeft,
                    size = cellSize,
                    cornerRadius = corner,
                    style = Stroke(width = stroke),
                )
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PaneLayoutGlyphPreview() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WorkspaceDesign.Layout.entries.forEach { layout ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = layout.name,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.size(width = 120.dp, height = 16.dp),
                )
                paneCells(layout).indices.forEach { paneIndex ->
                    PaneLayoutGlyph(layout = layout, paneIndex = paneIndex)
                }
            }
        }
    }
}

/**
 * Blown up, because the shipped size is too small to review the cell shapes at.
 */
@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PaneLayoutGlyphLargePreview() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WorkspaceDesign.Layout.entries.forEach { layout ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                paneCells(layout).indices.forEach { paneIndex ->
                    PaneLayoutGlyph(
                        layout = layout,
                        paneIndex = paneIndex,
                        width = 66.dp,
                        height = 48.dp,
                    )
                }
            }
        }
    }
}

/**
 * The mirror is invisible in an LTR preview: a forgotten flip still draws a plausible diagram, just
 * the wrong one. TRIPLE_MAIN_LEFT's main pane has to sit on the physical right here.
 */
@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PaneLayoutGlyphRtlPreview() {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            listOf(
                WorkspaceDesign.Layout.DUAL_VERTICAL,
                WorkspaceDesign.Layout.TRIPLE_MAIN_LEFT,
                WorkspaceDesign.Layout.QUAD_GRID,
            ).forEach { layout ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    paneCells(layout).indices.forEach { paneIndex ->
                        PaneLayoutGlyph(
                            layout = layout,
                            paneIndex = paneIndex,
                            width = 66.dp,
                            height = 48.dp,
                        )
                    }
                }
            }
        }
    }
}
