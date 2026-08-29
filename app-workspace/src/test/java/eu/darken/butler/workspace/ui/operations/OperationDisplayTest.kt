package eu.darken.butler.workspace.ui.operations

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.Operation
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

class OperationDisplayTest : BaseTest() {

    private fun completed(failure: Throwable?) = object : Operation.State.Completed {
        override val startedAt = Clock.System.now()
        override val completedAt = Clock.System.now()
        override val summary = "summary".toCaString()
        override val report: Operation.Report? = null
        override val error = failure
    }

    private fun managedOp(opState: Operation.State): ManagedOperation {
        val op = mockk<ManagedOperation>()
        every { op.id } returns Operation.Id()
        every { op.state } returns MutableStateFlow(opState)
        every { op.canCancel } returns false
        every { op.metadata } returns mockk {
            every { icon } returns mockk()
            every { title } returns "op".toCaString()
            every { description } returns "desc".toCaString()
            every { pathPlan } returns null
        }
        return op
    }

    @Test
    fun `a cancelled run is cancelled, not failed`() {
        // What a user who declines an install confirmation or cancels a copy ends up with. History
        // and the failure notification already read it as cancelled.
        val display = managedOp(completed(CancellationException("The user declined the install"))).toDisplayModel()

        display.state.shouldBeInstanceOf<OperationDisplay.State.Cancelled>()
    }

    @Test
    fun `a successful run without a report is completed`() {
        // An install reports no path changes, so it completes with a null report.
        val display = managedOp(completed(null)).toDisplayModel()

        display.state.shouldBeInstanceOf<OperationDisplay.State.Completed>().report.shouldBeNull()
    }

    @Test
    fun `a run that ended on an error is failed`() {
        val display = managedOp(completed(IOException("No space left"))).toDisplayModel()

        display.state.shouldBeInstanceOf<OperationDisplay.State.Failed>()
    }
}
