package eu.darken.butler.workspace.core.serialization

import eu.darken.butler.workspace.core.Workspace
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.uuid.Uuid

/**
 * Serializer for Workspace.Id that uses the full UUID, not the display string.
 */
object WorkspaceIdSerializer : KSerializer<Workspace.Id> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Workspace.Id", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Workspace.Id) {
        encoder.encodeString(value.id.toString())
    }

    override fun deserialize(decoder: Decoder): Workspace.Id {
        return Workspace.Id(Uuid.parse(decoder.decodeString()))
    }
}
