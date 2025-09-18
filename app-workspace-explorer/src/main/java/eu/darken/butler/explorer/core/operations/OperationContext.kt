package eu.darken.butler.explorer.core.operations

import eu.darken.butler.explorer.core.engine.ExplorerOperation
import eu.darken.butler.explorer.core.operations.handlers.BaseOperationHandler
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Context object that encapsulates operation execution state and capabilities.
 * This avoids passing multiple parameters through operation handler chains.
 */
data class OperationContext(
    val operationId: OperationId,
    val startedAt: Instant = Clock.System.now(),
    private val emitState: suspend (OperationState) -> Unit,
    private val emitHint: suspend (OperationNotifier.Hint) -> Unit,
) {
    suspend fun emit(state: OperationState) {
        emitState(state)
    }

    suspend fun emit(hint: OperationNotifier.Hint) {
        emitHint(hint)
    }

    /**
     * Extension function to execute operation handlers within this context.
     * This enables the pattern: with(context) { handler.execute(operation) }
     */
    suspend fun <T : ExplorerOperation> BaseOperationHandler<T>.execute(
        operation: T
    ) = execute(this@OperationContext, operation)
}