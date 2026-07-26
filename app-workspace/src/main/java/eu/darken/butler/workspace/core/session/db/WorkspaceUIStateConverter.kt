package eu.darken.butler.workspace.core.session.db

import androidx.room.ProvidedTypeConverter
import androidx.room.TypeConverter
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.serialization.WorkspaceIdSerializer
import eu.darken.butler.workspace.ui.scroll.WorkspaceScrollPosition
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
     *
     * Each field is decoded on its own rather than the object in one go, because the fields are not
     * worth the same: focus and pane selection are one id each and are what the user actually
     * notices, while the scroll and bar maps are large, nested and by far the likeliest to break.
     * Decoding the whole blob at once let one bad scroll entry take the focus down with it.
     *
     * The returned state always carries [WorkspaceUIState.CURRENT_VERSION] - it describes what this
     * build decoded, so a newer marker is reported but not carried forward into a blob we wrote.
     */
    @TypeConverter
    fun toUIState(value: String): WorkspaceUIState {
        val root = try {
            json.parseToJsonElement(value).jsonObject
        } catch (e: Exception) {
            log(TAG, WARN) {
                "Persisted UI state is not a readable JSON object, ALL of it is DISCARDED " +
                    "(focus, panes, scroll positions and bar collapse): ${e.asLog()}"
            }
            return WorkspaceUIState()
        }

        reportVersion(root)

        return WorkspaceUIState(
            focusedWorkspaceId = root.decodeField(FIELD_FOCUSED, WorkspaceIdSerializer.nullable, null),
            paneSelections = root.decodeField(FIELD_PANES, PANE_SELECTIONS, emptyMap()),
            scrollPositions = root.decodeField(FIELD_SCROLL, SCROLL_POSITIONS, emptyMap()),
            barCollapse = root.decodeField(FIELD_BARS, BAR_COLLAPSE, emptyMap()),
        )
    }

    private fun <T> JsonObject.decodeField(name: String, serializer: KSerializer<T>, fallback: T): T {
        val element = this[name] ?: return fallback
        return try {
            json.decodeFromJsonElement(serializer, element)
        } catch (e: Exception) {
            log(TAG, WARN) {
                "Persisted UI state field '$name' DISCARDED, the other fields are kept: ${e.asLog()}"
            }
            fallback
        }
    }

    /**
     * A newer marker is called out separately: it predicts exactly the fields this build silently
     * does not know about, and it is the one case where lost state is expected rather than a bug.
     */
    private fun reportVersion(root: JsonObject) {
        val stored = try {
            root[FIELD_VERSION]?.jsonPrimitive?.int
        } catch (e: Exception) {
            log(TAG, WARN) {
                "Persisted UI state has an unreadable '$FIELD_VERSION', reading it as unversioned: ${e.asLog()}"
            }
            null
        }
        when {
            stored == null -> log(TAG, VERBOSE) {
                "Persisted UI state is unversioned, reading it as v${WorkspaceUIState.CURRENT_VERSION}"
            }

            stored > WorkspaceUIState.CURRENT_VERSION -> log(TAG, WARN) {
                "Persisted UI state was written by a NEWER build (v$stored > v${WorkspaceUIState.CURRENT_VERSION}): " +
                    "anything this build does not know is dropped and the next save rewrites the row as " +
                    "v${WorkspaceUIState.CURRENT_VERSION}. Expected after going back from a newer build, not corruption."
            }

            else -> log(TAG, VERBOSE) { "Persisted UI state is v$stored" }
        }
    }

    companion object {
        private val TAG = logTag("Workspace", "Session", "Storage", "UIStateConverter")

        private const val FIELD_VERSION = "version"
        private const val FIELD_FOCUSED = "focusedWorkspaceId"
        private const val FIELD_PANES = "paneSelections"
        private const val FIELD_SCROLL = "scrollPositions"
        private const val FIELD_BARS = "barCollapse"

        // Mirrors of the field types in WorkspaceUIState - they are handed straight to its
        // constructor, so a type change there is a compile error here rather than a silent drift.
        private val PANE_SELECTIONS = MapSerializer(Int.serializer(), WorkspaceIdSerializer)
        private val SCROLL_POSITIONS = MapSerializer(
            WorkspaceIdSerializer,
            MapSerializer(String.serializer(), WorkspaceScrollPosition.serializer()),
        )
        private val BAR_COLLAPSE = MapSerializer(
            WorkspaceIdSerializer,
            MapSerializer(String.serializer(), MapSerializer(String.serializer(), Float.serializer())),
        )
    }
}
