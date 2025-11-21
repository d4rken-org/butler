package eu.darken.butler.workspace.core.serialization

import androidx.room.TypeConverter
import eu.darken.butler.workspace.core.Workspace
import kotlin.uuid.Uuid

/**
 * Type converter for Workspace.Id
 * Uses the full underlying UUID, NOT the display string from .toString()
 */
class WorkspaceIdConverter {

    @TypeConverter
    fun fromId(value: Workspace.Id?): String? = value?.id?.toString()

    @TypeConverter
    fun toId(value: String?): Workspace.Id? = value?.let { Workspace.Id(Uuid.Companion.parse(it)) }
}