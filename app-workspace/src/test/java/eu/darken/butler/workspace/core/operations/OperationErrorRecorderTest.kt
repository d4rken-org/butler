package eu.darken.butler.workspace.core.operations

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.error.recordingIncidentStore
import java.io.IOException
import kotlin.time.Clock

/**
 * The subscription is application-scoped, so an operation that fails while no page is open still
 * gets its report frozen at the moment it failed.
 */
class OperationErrorRecorderTest : BaseTest() {

    private val completed = MutableSharedFlow<CompletedOperationSnapshot>(replay = 1)
    private val operationsManager = mockk<OperationsManager>().apply {
        every { completedOperations } returns completed
    }

    private fun snapshot(error: Throwable?) = CompletedOperationSnapshot(
        id = Operation.Id(),
        metadata = mockk {
            every { origin } returns Operation.Metadata.Origin.Explorer(Workspace.Id())
        },
        state = object : Operation.State.Completed {
            override val startedAt = Clock.System.now()
            override val completedAt = Clock.System.now()
            override val summary = "done".toCaString()
            override val report: Operation.Report? = null
            override val error: Throwable? = error
        },
    )

    @Test
    fun `a failed operation is frozen with the time it completed`() = runTest {
        val store = recordingIncidentStore()
        OperationErrorRecorder(backgroundScope, operationsManager, store)
        val boom = IOException("boom")
        val snapshot = snapshot(boom)

        completed.emit(snapshot)
        runCurrent()

        val incident = store.get(boom).shouldNotBeNull()
        incident.occurredAt shouldBe snapshot.state.completedAt
        incident.occurredAtIsApproximate shouldBe false
        incident.context["op.id"] shouldBe snapshot.id.toString()
        incident.context["op.completedAt"] shouldBe snapshot.state.completedAt.toString()
    }

    @Test
    fun `a cancelled operation is not frozen`() = runTest {
        val store = recordingIncidentStore()
        OperationErrorRecorder(backgroundScope, operationsManager, store)
        val cancelled = CancellationException("user pressed cancel")

        completed.emit(snapshot(cancelled))
        runCurrent()

        store.get(cancelled) shouldBe null
    }
}
