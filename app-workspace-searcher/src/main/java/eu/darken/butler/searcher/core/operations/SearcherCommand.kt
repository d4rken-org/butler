package eu.darken.butler.searcher.core.operations

import eu.darken.butler.common.files.APath

sealed interface SearcherCommand {
    data class Delete(
        val targets: Set<APath<*>>,
    ) : SearcherCommand
}
