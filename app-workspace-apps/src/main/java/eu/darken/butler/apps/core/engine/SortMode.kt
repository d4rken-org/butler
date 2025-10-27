package eu.darken.butler.apps.core.engine

import kotlinx.serialization.Serializable

@Serializable
enum class SortMode {
    NAME_ASC,
    NAME_DESC,
    SIZE_ASC,
    SIZE_DESC,
    INSTALL_DATE,
    UPDATE_DATE,
    PACKAGE_NAME,
    ;
}
