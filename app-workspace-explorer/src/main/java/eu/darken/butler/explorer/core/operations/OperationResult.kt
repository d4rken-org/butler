package eu.darken.butler.explorer.core.operations

sealed interface OperationResult {
    val metrics: OperationMetrics

    data class Success(
        override val metrics: OperationMetrics,
    ) : OperationResult

    data class Failure(
        override val metrics: OperationMetrics,
        val exception: Exception,
    ) : OperationResult

    data class Cancelled(
        override val metrics: OperationMetrics,
    ) : OperationResult
}