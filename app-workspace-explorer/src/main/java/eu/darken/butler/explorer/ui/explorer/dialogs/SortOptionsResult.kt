package eu.darken.butler.explorer.ui.explorer.dialogs

import eu.darken.butler.explorer.core.SortSettings

/**
 * How far the chosen sort reaches. Orthogonal to `onlyThisTab`, which turns any of these into its
 * tab-local twin.
 */
enum class SortScope {
    /** Drops this folder's own rule and writes the default. Never touches a covering ancestor rule. */
    ALL_FOLDERS,
    THIS_FOLDER,
    THIS_FOLDER_AND_SUBFOLDERS,

    /** Suppresses rules at and above this folder, falling through to the default. */
    USE_DEFAULT_HERE,
    ;
}

data class SortOptionsResult(
    val sortSettings: SortSettings,
    val scope: SortScope = SortScope.ALL_FOLDERS,
    val onlyThisTab: Boolean = false,
)
