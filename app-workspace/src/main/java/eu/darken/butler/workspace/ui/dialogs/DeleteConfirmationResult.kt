package eu.darken.butler.workspace.ui.dialogs

import eu.darken.butler.common.files.APath

data class DeleteConfirmationResult(
    val items: Set<APath>,
)
