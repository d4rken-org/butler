package eu.darken.butler.apps.ui.apps.dialogs

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.files.APath

/**
 * Represents a path associated with an app (data directory, external storage, etc.)
 */
data class AppPath(
    val path: APath<*>,
    val label: CaString,
)
