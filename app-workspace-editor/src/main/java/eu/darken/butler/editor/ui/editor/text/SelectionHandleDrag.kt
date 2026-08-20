package eu.darken.butler.editor.ui.editor.text

import eu.darken.butler.editor.core.engine.TextPosition

/**
 * Orders two UI-produced positions by line, then column.
 *
 * Deliberately NOT by [TextPosition.offset]: positions that come out of
 * [calculatePositionFromOffset] are built by [createUiTextPosition], which leaves the offset as a
 * placeholder - `EditorEngine.setSelection` resolves the real one afterwards. Comparing offsets
 * here would compare two zeroes.
 */
internal fun compareUiPositions(a: TextPosition, b: TextPosition): Int = when {
    a.line != b.line -> a.line.compareTo(b.line)
    else -> a.column.compareTo(b.column)
}

/**
 * The two positions as a start <= end pair. Selections are stored ordered everywhere downstream
 * (`EditorEngine.normalized()`, `computeFieldSelectionSync`, `selectedCharacterCount`), so a drag
 * that crosses over has to hand back the SWAPPED pair, not its own start-to-end order.
 */
internal fun orderedSelection(a: TextPosition, b: TextPosition): Pair<TextPosition, TextPosition> =
    if (compareUiPositions(a, b) <= 0) a to b else b to a

/**
 * Tracks both selection handles together, because each emitted selection is a COMPLETE pair and
 * two fingers can be dragging at the same time.
 *
 * Per handle it keeps the position the finger is on ([Slot.moving]) and the endpoint that gesture
 * pivots around ([Slot.anchor]). The anchor is captured once at begin and released at end: reading
 * it back out of the live selection instead breaks the moment the finger crosses it, because the
 * pair is ordered and the endpoints swap slots. With a selection of columns 10..20 and the right
 * handle dragged left: at column 15 the selection becomes (10, 15), at column 5 it becomes
 * (5, 10) - and now the moving endpoint sits in the slot the anchor occupied. The next event reads
 * 5 as its anchor and yields (4, 5), the one after that (3, 4): a one-character selection that
 * follows the finger and drags the other handle along with it.
 *
 * The other endpoint of an emitted pair is therefore looked up per event: the PEER finger's latest
 * position while that finger is down, the frozen anchor otherwise. Emitting the frozen anchor
 * unconditionally would make each event undo whatever the other finger did since this gesture
 * started, so a two-finger drag would alternate between (movingStart, originalEnd) and
 * (originalStart, movingEnd) instead of composing.
 *
 * Plain fields rather than snapshot state: nothing composes off this, so it must not recompose.
 */
internal class SelectionDragCoordinator {

    private class Slot {
        var active: Boolean = false
        var anchor: TextPosition? = null
        var moving: TextPosition? = null
    }

    private val startSlot = Slot()
    private val endSlot = Slot()

    fun beginStart(currentStart: TextPosition, currentEnd: TextPosition) = begin(
        slot = startSlot,
        moving = currentStart,
        anchor = currentEnd,
    )

    fun beginEnd(currentStart: TextPosition, currentEnd: TextPosition) = begin(
        slot = endSlot,
        moving = currentEnd,
        anchor = currentStart,
    )

    /**
     * The selection for the start-handle finger at [moving], or null when no gesture is running on
     * that handle.
     *
     * There is no fallback to a live endpoint on purpose: `detectDragGestures` always calls
     * `onDragStart` before the first `onDrag`, so a missing gesture means the lifecycle wiring
     * broke - and guessing an anchor from the current selection would reintroduce exactly the
     * drift described above. Callers skip the selection update instead.
     */
    fun updateStart(moving: TextPosition): Pair<TextPosition, TextPosition>? = update(
        slot = startSlot,
        peer = endSlot,
        moving = moving,
    )

    /** Mirror image of [updateStart] for the end handle. */
    fun updateEnd(moving: TextPosition): Pair<TextPosition, TextPosition>? = update(
        slot = endSlot,
        peer = startSlot,
        moving = moving,
    )

    fun endStart() = end(slot = startSlot, peer = endSlot)

    fun endEnd() = end(slot = endSlot, peer = startSlot)

    private fun begin(slot: Slot, moving: TextPosition, anchor: TextPosition) {
        slot.active = true
        slot.moving = moving
        slot.anchor = anchor
    }

    private fun update(slot: Slot, peer: Slot, moving: TextPosition): Pair<TextPosition, TextPosition>? {
        if (!slot.active) return null
        slot.moving = moving
        val other = (if (peer.active) peer.moving else slot.anchor) ?: return null
        return orderedSelection(other, moving)
    }

    /**
     * Ends [slot]'s gesture, handing its final position over to a [peer] that is still down.
     * Without that handover the surviving finger would fall back to an anchor describing where the
     * lifted finger STARTED, snapping the selection back over everything that finger moved.
     */
    private fun end(slot: Slot, peer: Slot) {
        if (!slot.active) return
        val finished = slot.moving
        if (peer.active && finished != null) {
            peer.anchor = finished
        }
        slot.active = false
        slot.anchor = null
        slot.moving = null
    }
}
