package eu.darken.butler.explorer.core.operations

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.explorer.core.errors.ExplorerError

sealed class OperationResult {
    abstract val metrics: OperationMetrics
    
    data class Success(
        override val metrics: OperationMetrics,
        val affectedPaths: List<APath> = emptyList(),
        val summary: CaString? = null,
    ) : OperationResult()
    
    data class Failure(
        override val metrics: OperationMetrics,
        val error: ExplorerError,
        val exception: Exception? = null,
        val failedPath: APath? = null,
        val partialResults: List<APath> = emptyList(),
        val isRecoverable: Boolean = false,
        val suggestion: CaString? = null,
    ) : OperationResult()
    
    data class Cancelled(
        override val metrics: OperationMetrics,
        val cancelledAt: APath? = null,
        val completedPaths: List<APath> = emptyList(),
    ) : OperationResult()
}