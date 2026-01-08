package eu.darken.butler.developer.core.operations

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.workspace.core.operations.Operation
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock
import kotlin.time.Instant

abstract class DeveloperOperation : Operation {

    interface Report : Operation.Report

    abstract override fun perform(operationContext: Operation.Context): Flow<State>

    sealed interface State : Operation.State {
        data class Active(
            override val startedAt: Instant,
            override val primaryProgress: Progress.Data = Progress.Data(),
            override val secondaryProgress: Progress.Data? = null,
        ) : State, Operation.State.Active

        data class Completed(
            override val startedAt: Instant,
            override val completedAt: Instant = Clock.System.now(),
            override val error: Throwable? = null,
            override val report: Report,
        ) : State, Operation.State.Completed {
            override val summary: CaString get() = report.summary
        }
    }
}
