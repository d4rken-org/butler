package eu.darken.butler.workspace.core

import eu.darken.butler.common.ca.CaString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Tab identity derived from a workspace's arguments alone: what the user sees before (and while)
 * the workspace itself exists. A null field means "nothing identifying in the arguments" and the
 * caller falls back to [Workspace.Type.label].
 */
data class WorkspaceDisplay(
    val title: CaString? = null,
    val subtitle: CaString? = null,
)

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

    /**
     * Identity for [arguments] without instantiating the workspace, so a paused stand-in can name
     * itself the way its live counterpart does.
     *
     * MUST be synchronous and side-effect free: no filesystem, PackageManager or ContentResolver
     * access. It runs during session restore and while seeding [Workspace.Info]. The workspace type
     * that owns the arguments implements this once and uses it for BOTH this override and its own
     * `initialInfo` seed, so the paused and live identities cannot drift.
     *
     * Returning null (or a [WorkspaceDisplay] with null fields) means the arguments carry nothing
     * identifying and the caller falls back to [Workspace.Type.label].
     *
     * Implement as a defaulted method so `create` stays the only abstract method, as required by
     * @AssistedFactory.
     */
    fun deriveDisplay(arguments: ArgT): WorkspaceDisplay? = null

    fun serialize(json: Json, arguments: ArgT): JsonElement =
        json.encodeToJsonElement(argumentsSerializer, arguments)

    fun deserialize(json: Json, element: JsonElement): ArgT =
        json.decodeFromJsonElement(argumentsSerializer, element)
}
