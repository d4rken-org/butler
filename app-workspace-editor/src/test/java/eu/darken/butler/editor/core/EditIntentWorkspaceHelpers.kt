package eu.darken.butler.editor.core

import eu.darken.butler.editor.core.engine.EditorEngine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlin.uuid.Uuid

/** Epoch of the document the workspace currently publishes - what the ViewModel stamps edits with. */
internal suspend fun EditorWorkspace.currentEpoch(): Uuid = state
    .filterIsInstance<EditorWorkspace.State.Ready>()
    .map { it.editor.windowToken }
    .mapNotNull { it?.engineEpoch }
    .first()

internal suspend fun EditorWorkspace.performInsert(text: String): EditorEngine.EditOutcome =
    performEdit(EditorEngine.EditIntent.InsertAtCursor(text), currentEpoch())
