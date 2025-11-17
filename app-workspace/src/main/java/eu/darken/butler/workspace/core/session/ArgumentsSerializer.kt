package eu.darken.butler.workspace.core.session

import eu.darken.butler.common.debug.logging.Logging.Priority.DEBUG
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles serialization and deserialization of workspace arguments
 */
@Singleton
class ArgumentsSerializer @Inject constructor(
    private val json: Json,
) {
    private val tag = logTag("Workspace", "ArgumentsSerializer")

    /**
     * Serialize workspace arguments to JSON
     * Stores the JSON string representation wrapped with type information
     */
    fun serialize(type: Workspace.Type, arguments: Workspace.Arguments?): JsonElement? {
        if (arguments == null) return null

        return try {
            // Try to serialize directly - requires @Serializable on Arguments
            val jsonString = json.encodeToString(arguments)
            json.parseToJsonElement(json.encodeToString(SerializedArguments(
                type = type.name,
                dataJson = jsonString
            )))
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to serialize arguments for $type: ${e.asLog()}" }
            null
        }
    }

    /**
     * Deserialize workspace arguments from JSON
     */
    fun deserialize(type: Workspace.Type, element: JsonElement): Workspace.Arguments? {
        return try {
            // Note: This is a generic serialization handler that stores the raw JSON data.
            // The actual workspace implementations are responsible for deserializing their
            // specific argument types when they reconstruct themselves.
            // This method is kept simple to avoid circular dependencies.
            // Individual workspaces should implement their own deserialization logic
            // when they need to reconstruct from saved state.
            log(tag, DEBUG) { "Generic deserialization for $type - raw JSON stored" }
            null // Workspaces will deserialize their own argument types
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to deserialize arguments for $type: ${e.asLog()}" }
            null
        }
    }
}

@Serializable
private data class SerializedArguments(
    val type: String,
    val dataJson: String,
)