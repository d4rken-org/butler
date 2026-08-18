package eu.darken.butler.viewer.core.operations

import eu.darken.butler.common.files.APath

sealed interface ViewerCommand {

    /**
     * Deletes the file the viewer is showing. A set rather than a single path so it maps straight
     * onto [eu.darken.butler.workspace.core.operations.CoreDeleteExecutor], which the Explorer and
     * Searcher delete operations share.
     */
    data class Delete(
        val targets: Set<APath<*>>,
        val options: Options = Options(),
    ) : ViewerCommand {
        data class Options(
            val forcePermDelete: Boolean = false,
        )
    }
}
