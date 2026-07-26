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

    /**
     * A blob that can't be read is dropped, never rethrown: stale UI state must not keep the app
     * from starting. The trade-off is that a format change looks like "everything was just
     * expanded/scrolled to the top", so it has to be loud in the log to be recognizable.
     */
    @TypeConverter
    fun toUIState(value: String): WorkspaceUIState {
        return try {
            json.decodeFromString(WorkspaceUIState.serializer(), value)
        } catch (e: Exception) {
            log(TAG, WARN) {
                "Persisted UI state DISCARDED (scroll positions and bar collapse are lost), " +
                    "most likely a change to the stored JSON format: ${e.asLog()}"
            }
            WorkspaceUIState()
        }
    }

    companion object {
        private val TAG = logTag("Workspace", "Session", "Storage", "UIStateConverter")
    }
}
