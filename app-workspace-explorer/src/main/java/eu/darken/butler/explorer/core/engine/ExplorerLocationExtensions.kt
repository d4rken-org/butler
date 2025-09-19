package eu.darken.butler.explorer.core.engine

val ExplorerLocation.locationId: String
    get() = when (this) {
        is ExplorerLocation.Home -> "location://home"
        is ExplorerLocation.Device -> "location://device"
        is ExplorerLocation.Directory -> "location://directory/${path.path}"
    }