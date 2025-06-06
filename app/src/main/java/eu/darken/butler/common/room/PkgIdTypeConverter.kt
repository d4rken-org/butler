package eu.darken.butler.common.room

import androidx.room.TypeConverter
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.toPkgId

class PkgIdTypeConverter {
    @TypeConverter
    fun from(value: Pkg.Id): String = value.name

    @TypeConverter
    fun to(value: String): Pkg.Id = value.toPkgId()
}