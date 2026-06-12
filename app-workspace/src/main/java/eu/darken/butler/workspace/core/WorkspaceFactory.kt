package eu.darken.butler.workspace.core

import kotlinx.serialization.KSerializer
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
     * Serializer for the concrete Arguments type.
     * Implement as a defaulted property (`get() = ...`) so `create` stays
     * the only abstract method, as required by @AssistedFactory.
     */
    val argumentsSerializer: KSerializer<ArgT>

    fun serialize(json: Json, arguments: ArgT): JsonElement =
        json.encodeToJsonElement(argumentsSerializer, arguments)

    fun deserialize(json: Json, element: JsonElement): ArgT =
        json.decodeFromJsonElement(argumentsSerializer, element)
}
