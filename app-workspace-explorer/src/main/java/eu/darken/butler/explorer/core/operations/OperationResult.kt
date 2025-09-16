package eu.darken.butler.explorer.core.operations

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.files.APath

sealed class OperationResult {
    abstract val metrics: OperationMetrics
    
    data class Success(
        override val metrics: OperationMetrics,
        val affectedPaths: List<APath> = emptyList(), // TODO: Provide this information
        val summary: CaString? = null,
    ) : OperationResult()
    
    data class Failure(
        override val metrics: OperationMetrics,
        val exception: Exception,
        val failedPath: APath? = null,
        val partialResults: List<APath> = emptyList(), // TODO: Provide this information
        val isRecoverable: Boolean = false,
        val suggestion: CaString? = null,
    ) : OperationResult()
    
    data class Cancelled(
        override val metrics: OperationMetrics,
        val cancelledAt: APath? = null,
        val completedPaths: List<APath> = emptyList(), // TODO: Provide this information
    ) : OperationResult()
}