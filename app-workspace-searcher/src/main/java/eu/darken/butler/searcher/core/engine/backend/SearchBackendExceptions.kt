package eu.darken.butler.searcher.core.engine.backend

import eu.darken.butler.workspace.contracts.searcher.FilterCondition
import eu.darken.butler.workspace.contracts.searcher.SearchTarget

class UnsupportedTargetException(target: SearchTarget) :
    IllegalArgumentException("No search backend can handle target: $target")

class UnsupportedFilterException(condition: FilterCondition) :
    IllegalArgumentException("The selected backend does not support filter: $condition")
