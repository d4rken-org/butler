package eu.darken.butler.common.files.room

import androidx.room.TypeConverter
import eu.darken.butler.common.files.metadata.FileType

class FileTypeConverter {

    @TypeConverter
    fun fromFileType(fileType: FileType): String = fileType.name

    @TypeConverter
    fun toFileType(string: String): FileType = FileType.valueOf(string)

}
