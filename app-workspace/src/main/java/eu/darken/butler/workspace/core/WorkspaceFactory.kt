package eu.darken.butler.workspace.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Base interface for all workspace factories.
 * Each workspace type provides a factory that creates workspace instances
 * and handles serialization/deserialization of its Arguments type.
 */
interface WorkspaceFactory<ArgT : Workspace.Arguments> {
    /**
     * Create a workspace instance with the given ID and arguments
     */
    fun create(id: Workspace.Id, arguments: ArgT): Workspace<ArgT>

    /**
     * Serialize workspace arguments to JSON.
     * Each factory knows its concrete Arguments type and can serialize it properly.
     */
    fun serialize(json: Json, arguments: ArgT): JsonElement

    /**
     * Deserialize JSON to workspace arguments.
     * Each factory knows its concrete Arguments type and can deserialize it properly.
     */
    fun deserialize(json: Json, element: JsonElement): ArgT
}
