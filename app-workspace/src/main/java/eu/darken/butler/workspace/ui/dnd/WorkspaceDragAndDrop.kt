package eu.darken.butler.workspace.ui.dnd

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropSourceModifierNode
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.CacheDrawModifierNode
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.contracts.dnd.WorkspaceDragPayload

/**
 * The payload rides along as [DragAndDropTransferData.localState], the ClipData only carries a
 * label plus an item count so a drag leaving the app can't expose any path.
 */
fun WorkspaceDragPayload.toTransferData(): DragAndDropTransferData = DragAndDropTransferData(
    clipData = ClipData.newPlainText(WorkspaceDragPayload.CLIP_LABEL, "${items.size}"),
    localState = this,
)

fun DragAndDropEvent.workspaceDragPayload(): WorkspaceDragPayload? =
    toAndroidDragEvent().localState as? WorkspaceDragPayload

/** Pointer position in pixels relative to the Compose root, matching `LayoutCoordinates.boundsInRoot()`. */
fun DragAndDropEvent.positionInRoot(): Offset = toAndroidDragEvent().let { Offset(it.x, it.y) }

/**
 * A drag that the caller starts itself, instead of leaving it to a built-in gesture detector.
 *
 * Items in Butler are clickable and long-clickable, and `combinedClickable` consumes the pointer
 * DOWN in the main pass — the detector behind `Modifier.dragAndDropSource` waits for an unconsumed
 * DOWN and therefore never fires, in either nesting order. So the long-press that already works
 * arms the transfer: it runs while the pointer is still down, which is what the platform needs to
 * latch the drag onto the live pointer stream.
 *
 * Attach [modifier] to the composable being dragged (it doubles as the drag shadow) and call
 * [startDrag] from its long-click callback.
 */
class WorkspaceDragSource internal constructor(
    internal val payloadProvider: () -> WorkspaceDragPayload?,
) {

    internal var node: WorkspaceDragSourceNode? = null

    val modifier: Modifier = WorkspaceDragSourceElement(this)

    /**
     * Starts the platform drag for the current payload. No-op while [modifier] isn't attached or
     * when there is nothing to drag.
     */
    fun startDrag() {
        node?.startTransfer()
    }
}

/**
 * Remembers a [WorkspaceDragSource] whose payload is resolved when the drag actually starts, so it
 * always reflects the latest state the caller has collected.
 */
@Composable
fun rememberWorkspaceDragSource(payloadProvider: () -> WorkspaceDragPayload?): WorkspaceDragSource {
    val currentProvider by rememberUpdatedState(payloadProvider)
    return remember { WorkspaceDragSource { currentProvider() } }
}

private class WorkspaceDragSourceElement(
    private val source: WorkspaceDragSource,
) : ModifierNodeElement<WorkspaceDragSourceNode>() {

    override fun create() = WorkspaceDragSourceNode(source)

    override fun update(node: WorkspaceDragSourceNode) = node.updateSource(source)

    override fun InspectorInfo.inspectableProperties() {
        name = "workspaceDragSource"
    }

    override fun equals(other: Any?): Boolean = other is WorkspaceDragSourceElement && other.source === source

    override fun hashCode(): Int = source.hashCode()
}

internal class WorkspaceDragSourceNode(
    private var source: WorkspaceDragSource,
) : DelegatingNode(), LayoutAwareModifierNode {

    private var size: IntSize = IntSize.Zero
    private val dragShadow = DragShadowCallback()

    private val dragAndDropNode = delegate(
        DragAndDropSourceModifierNode {
            val payload = source.payloadProvider()
            if (payload != null) {
                startDragAndDropTransfer(
                    transferData = payload.toTransferData(),
                    decorationSize = size.toSize(),
                    drawDragDecoration = { dragShadow.drawDragShadow(this) },
                )
            }
        }
    )

    init {
        delegate(CacheDrawModifierNode(dragShadow::cachePicture))
    }

    override fun onAttach() {
        source.node = this
    }

    override fun onDetach() {
        if (source.node === this) source.node = null
    }

    fun updateSource(source: WorkspaceDragSource) {
        if (this.source === source) return
        if (this.source.node === this) this.source.node = null
        this.source = source
        if (isAttached) source.node = this
    }

    /** [Offset.Unspecified] skips the hit-test against the pointer position, the payload ignores it. */
    fun startTransfer() {
        if (!isAttached) return
        dragAndDropNode.requestDragAndDropTransfer(Offset.Unspecified)
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        dragAndDropNode.onPlaced(coordinates)
    }

    override fun onRemeasured(size: IntSize) {
        this.size = size
        dragAndDropNode.onRemeasured(size)
    }
}

/**
 * Draws the dragged composable as its own drag shadow: the content is recorded into a graphics
 * layer while it renders, and that layer is replayed into the drag decoration.
 */
private class DragShadowCallback {

    private var layer: GraphicsLayer? = null

    fun drawDragShadow(scope: DrawScope) {
        val recorded = layer ?: return
        with(scope) { drawLayer(recorded) }
    }

    fun cachePicture(scope: CacheDrawScope): DrawResult = with(scope) {
        val recorded = obtainGraphicsLayer().apply { record { drawContent() } }
        layer = recorded
        onDrawWithContent { drawLayer(recorded) }
    }
}

/**
 * Drop target affordance, matching the pane drop highlight: primary border plus a light scrim.
 * Drawn after the content so it stays visible above opaque children.
 */
@Composable
fun Modifier.dropTargetHighlight(isHovered: Boolean): Modifier {
    val color = MaterialTheme.colorScheme.primary
    return this.drawWithContent {
        drawContent()
        if (!isHovered) return@drawWithContent
        val border = 3.dp.toPx()
        drawRect(color = color.copy(alpha = 0.1f))
        drawRect(
            color = color,
            topLeft = Offset(border / 2, border / 2),
            size = Size(size.width - border, size.height - border),
            style = Stroke(width = border),
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun DropTargetHighlightPreview() {
    PreviewWrapper {
        Box(
            modifier = Modifier
                .padding(16.dp)
                .width(240.dp)
                .height(120.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .dropTargetHighlight(isHovered = true),
        ) {
            Text(
                text = "Drop here",
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
