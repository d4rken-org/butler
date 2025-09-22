package eu.darken.butler.explorer.core.operations

import eu.darken.butler.common.ca.CaString
import kotlin.coroutines.cancellation.CancellationException

sealed interface OperationResult {
    data class Success(
        val summary: CaString,
    ) : OperationResult

    data class Failure(
        val exception: Exception,
    ) : OperationResult {
        val isCancelled: Boolean get() = exception is CancellationException
    }
}