package eu.darken.butler.editor.core.engine

import kotlin.uuid.Uuid

/**
 * Test-side equivalent of what the ViewModel does before enqueueing a mutation: stamp the intent
 * with the epoch of the document the engine currently publishes. [Uuid.NIL] stands in when nothing
 * is loaded - an engine without a window has nothing to accept an edit against anyway.
 */
internal val EditorEngine.currentEpoch: Uuid
    get() = visibleContent.value.token?.engineEpoch ?: Uuid.NIL

internal suspend fun EditorEngine.performInsert(text: String): EditorEngine.EditOutcome =
    performEdit(EditorEngine.EditIntent.InsertAtCursor(text), currentEpoch)

internal suspend fun EditorEngine.performDeleteSelection(): EditorEngine.EditOutcome =
    performEdit(EditorEngine.EditIntent.DeleteSelection, currentEpoch)

internal suspend fun EditorEngine.performDeleteForward(): EditorEngine.EditOutcome =
    performEdit(EditorEngine.EditIntent.DeleteForward, currentEpoch)

internal suspend fun EditorEngine.performUndo(): Result<EditOperation?> = undo(currentEpoch)

internal suspend fun EditorEngine.performRedo(): Result<EditOperation?> = redo(currentEpoch)
