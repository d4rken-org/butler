package eu.darken.butler.workspace.core.serialization

import androidx.room.TypeConverter
import eu.darken.butler.workspace.core.Workspace

/**
 * Type converter for Workspace.Type enum
 */
class WorkspaceTypeConverter {

    @TypeConverter
    fun fromType(value: Workspace.Type): String = value.name

    @TypeConverter
    fun toType(value: String): Workspace.Type = Workspace.Type.valueOf(value)
}