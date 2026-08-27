package eu.darken.butler.workspace.ui.operations

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Delete
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.core.operations.Operation
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.Test
import testhelpers.BaseTest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class OperationsDisplayStateTest : BaseTest() {

    private val ctx: Context = mockk()

    private fun createOp(
        state: OperationDisplay.State = OperationDisplay.State.Queued,
        startedAt: kotlin.time.Instant = Clock.System.now(),
        title: String = "Op",
    ) = OperationDisplay(
        id = Operation.Id(),
        title = title.toCaString(),
        description = "desc".toCaString(),
        icon = Icons.TwoTone.Delete,
        state = state,
        startedAt = startedAt,
    )

    private val now = Clock.System.now()
    private val dummyReport = object : Operation.Report {
        override val summary = "done".toCaString()
        override val affectedPaths = emptyList<Operation.Report.PathChange>()
        override val subjectPath = null
    }

    @Test
    fun `empty list stays empty`() {
        val result = emptyList<OperationDisplay>().sortedWith(operationDisplayComparator)
        result.shouldBeEmpty()
    }

    @Test
    fun `running operations come first`() {
        val running = createOp(state = OperationDisplay.State.Running(), title = "running")
        val queued = createOp(state = OperationDisplay.State.Queued, title = "queued")
        val completed = createOp(
            state = OperationDisplay.State.Completed(
                summary = "done".toCaString(),
                completedAt = now,
                report = dummyReport,
            ),
            title = "completed",
        )

        val sorted = listOf(completed, queued, running).sortedWith(operationDisplayComparator)
        sorted.map { it.title.get(ctx) } shouldBe listOf("running", "queued", "completed")
    }

    @Test
    fun `state priority order is Running, Waiting, Queued, Failed, Cancelled, Completed`() {
        val running = createOp(state = OperationDisplay.State.Running(), title = "running")
        val waiting = createOp(
            state = OperationDisplay.State.Waiting(reason = "conflict".toCaString()),
            title = "waiting",
        )
        val queued = createOp(state = OperationDisplay.State.Queued, title = "queued")
        val failed = createOp(
            state = OperationDisplay.State.Failed(
                summary = "err".toCaString(),
                completedAt = now,
                report = dummyReport,
            ),
            title = "failed",
        )
        val cancelled = createOp(
            state = OperationDisplay.State.Cancelled(completedAt = now, report = dummyReport),
            title = "cancelled",
        )
        val completed = createOp(
            state = OperationDisplay.State.Completed(
                summary = "done".toCaString(),
                completedAt = now,
                report = dummyReport,
            ),
            title = "completed",
        )

        // Feed in reverse order
        val input = listOf(completed, cancelled, failed, queued, waiting, running)
        val sorted = input.sortedWith(operationDisplayComparator)

        sorted.map { it.title.get(ctx) } shouldBe listOf(
            "running", "waiting", "queued", "failed", "cancelled", "completed",
        )
    }

    @Test
    fun `same state sorted by startedAt descending - newest first`() {
        val older = createOp(
            state = OperationDisplay.State.Running(),
            startedAt = now - 5.minutes,
            title = "older",
        )
        val newer = createOp(
            state = OperationDisplay.State.Running(),
            startedAt = now - 1.minutes,
            title = "newer",
        )
        val newest = createOp(
            state = OperationDisplay.State.Running(),
            startedAt = now,
            title = "newest",
        )

        val sorted = listOf(older, newest, newer).sortedWith(operationDisplayComparator)
        sorted.map { it.title.get(ctx) } shouldBe listOf("newest", "newer", "older")
    }

    @Test
    fun `state priority takes precedence over startedAt`() {
        val oldRunning = createOp(
            state = OperationDisplay.State.Running(),
            startedAt = now - 10.minutes,
            title = "old-running",
        )
        val newCompleted = createOp(
            state = OperationDisplay.State.Completed(
                summary = "done".toCaString(),
                completedAt = now,
                report = dummyReport,
            ),
            startedAt = now,
            title = "new-completed",
        )

        val sorted = listOf(newCompleted, oldRunning).sortedWith(operationDisplayComparator)
        sorted.map { it.title.get(ctx) } shouldBe listOf("old-running", "new-completed")
    }

    @Test
    fun `single operation returns unchanged`() {
        val op = createOp(title = "solo")
        val sorted = listOf(op).sortedWith(operationDisplayComparator)
        sorted.size shouldBe 1
        sorted[0] shouldBe op
    }

    @Test
    fun `mixed states with multiple operations per state`() {
        val run1 = createOp(state = OperationDisplay.State.Running(), startedAt = now - 1.seconds, title = "run1")
        val run2 = createOp(state = OperationDisplay.State.Running(), startedAt = now, title = "run2")
        val queue1 = createOp(state = OperationDisplay.State.Queued, startedAt = now - 2.seconds, title = "queue1")
        val queue2 = createOp(state = OperationDisplay.State.Queued, startedAt = now, title = "queue2")
        val done = createOp(
            state = OperationDisplay.State.Completed(
                summary = "done".toCaString(),
                completedAt = now,
                report = dummyReport,
            ),
            startedAt = now,
            title = "done",
        )

        val sorted = listOf(done, queue1, run1, queue2, run2).sortedWith(operationDisplayComparator)
        sorted.map { it.title.get(ctx) } shouldBe listOf("run2", "run1", "queue2", "queue1", "done")
    }
}
