package eu.darken.butler.explorer.ui.explorer.dialogs

import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel.FileTypeFilter

data class FilterOptionsResult(
    val includePattern: String,
    val excludePattern: String,
    val fileTypeFilter: FileTypeFilter,
)