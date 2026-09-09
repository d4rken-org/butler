package eu.darken.butler.workspace.core.operations

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * The one rule every tab marker and every workspace's info counters read: what counts as unfinished,
 * what of that is actually running, and what wants the user.
 */
class OperationCountsTest : BaseTest() {

    private fun operation(current: Operation.State): ManagedOperation = mockk {
        every { state } returns MutableStateFlow(current)
    }

    private fun queued() = operation(Operation.State.Queued(startedAt = Instant.fromEpochSeconds(0)))

    private fun active() = operation(mockk<Operation.State.Active>())

    private fun waiting() = operation(mockk<Operation.State.Waiting>())

    private fun completed(failure: Throwable? = null) = operation(
        mockk<Operation.State.Completed> { every { error } returns failure }
    )

    @Test
    fun `queued work has not begun`() {
        listOf(queued()).toOperationCounts() shouldBe OperationCounts(unfinished = 1, active = 0, attention = 0)
    }

    @Test
    fun `running work counts twice over`() {
        listOf(active()).toOperationCounts() shouldBe OperationCounts(unfinished = 1, active = 1, attention = 0)
    }

    @Test
    fun `work waiting on an answer is unfinished but not running`() {
        listOf(waiting()).toOperationCounts() shouldBe OperationCounts(unfinished = 1, active = 0, attention = 1)
    }

    @Test
    fun `a failure wants the user`() {
        listOf(completed(IllegalStateException("Disk full"))).toOperationCounts() shouldBe
            OperationCounts(unfinished = 0, active = 0, attention = 1)
    }

    @Test
    fun `a successful operation counts nowhere`() {
        listOf(completed()).toOperationCounts() shouldBe OperationCounts()
    }

    /** The user asked for it to stop, so it is neither a fault nor something to be told about. */
    @Test
    fun `a cancelled operation counts nowhere`() {
        listOf(completed(CancellationException("Cancelled"))).toOperationCounts() shouldBe OperationCounts()
    }

    @Test
    fun `counts add up across a mixed set`() {
        val operations = listOf(
            queued(),
            active(),
            active(),
            waiting(),
            completed(),
            completed(IllegalStateException("Disk full")),
            completed(CancellationException("Cancelled")),
        )

        operations.toOperationCounts() shouldBe OperationCounts(unfinished = 4, active = 2, attention = 2)
    }

    /** What the marker depends on: a spinner may never show where the counters say nothing runs. */
    @Test
    fun `running work is never more than unfinished work`() {
        val states: List<() -> ManagedOperation> = listOf(
            { queued() },
            { active() },
            { waiting() },
            { completed() },
            { completed(IllegalStateException("Disk full")) },
            { completed(CancellationException("Cancelled")) },
        )

        states.forEach { first ->
            states.forEach { second ->
                val counts = listOf(first(), second()).toOperationCounts()
                (counts.active <= counts.unfinished) shouldBe true
            }
        }
    }
}
