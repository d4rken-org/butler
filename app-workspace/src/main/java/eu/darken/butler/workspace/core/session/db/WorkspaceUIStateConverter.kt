package eu.darken.butler.workspace.core.session.db

import androidx.room.ProvidedTypeConverter
import androidx.room.TypeConverter
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Type converter for workspace UI state (focused workspace and pane selections)
 */
@ProvidedTypeConverter
class WorkspaceUIStateConverter @Inject constructor(
    private val json: Json
) {

    @TypeConverter
    fun fromUIState(value: WorkspaceUIState?): String {
        return json.encodeToString(WorkspaceUIState.serializer(), value ?: WorkspaceUIState())
    }

    @TypeConverter
    fun toUIState(value: String): WorkspaceUIState {
        return try {
            json.decodeFromString(WorkspaceUIState.serializer(), value)
        } catch (e: Exception) {
            log(TAG, ERROR) { "Failed to deserialize workspace UI state: ${e.asLog()}" }
            WorkspaceUIState()
        }
    }

    companion object {
        private val TAG = logTag("Workspace", "Session", "Storage", "UIStateConverter")
    }
}
