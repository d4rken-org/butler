package eu.darken.butler.workspace.core.operations

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Nothing obliges the user to dismiss a finished operation, so retention cannot be left to them:
 * these pin that finished operations and the observers watching them are released on their own.
 */
class OperationsManagerTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val epoch = Clock.System.now()

    private fun create() = OperationsManager(TestDispatcherProvider())

    /** Emits nothing until told to, so an operation stays unfinished for as long as a test needs. */
    private class FakeOperation(
        workspaceId: Workspace.Id,
        cancellable: Boolean = true,
        policy: Operation.Metadata.ClosePolicy = Operation.Metadata.ClosePolicy.CANCEL_WITH_ORIGIN,
    ) : Operation {
        // replay = 1: the collector subscribes when the manager starts the operation, a test finishes
        // it afterwards, and nothing should hinge on which of the two the dispatcher runs first.
        private val states = MutableSharedFlow<Operation.State>(replay = 1)

        override val metadata: Operation.Metadata = mockk<Operation.Metadata>().apply {
            every { origin } returns Operation.Metadata.Origin.Explorer(workspaceId)
            every { isCancellable } returns cancellable
            every { closePolicy } returns policy
        }

        override fun perform(operationContext: Operation.Context): Flow<Operation.State> = states

        suspend fun finish(at: Instant) = states.emit(
            object : Operation.State.Completed {
                override val startedAt: Instant = at
                override val completedAt: Instant = at
                override val summary = "done".toCaString()
                override val report: Operation.Report? = null
                override val error: Throwable? = null
            }
        )
    }

    private fun TestScope.recordCompletions(manager: OperationsManager): List<CompletedOperationSnapshot> {
        val seen = mutableListOf<CompletedOperationSnapshot>()
        backgroundScope.launch { manager.completedOperations.collect { seen.add(it) } }
        runCurrent()
        return seen
    }

    @Test
    fun `a finished operation releases its state observer`() = runTest {
        val manager = create()
        val operation = FakeOperation(workspaceId)

        val managed = manager.submitManaged(operation)
        manager.stateObservers.keys shouldContainExactly setOf(managed.id)

        operation.finish(epoch)
        runCurrent()

        manager.stateObservers.keys.shouldBeEmpty()
    }

    @Test
    fun `an unfinished operation keeps its state observer`() = runTest {
        val manager = create()

        val managed = manager.submitManaged(FakeOperation(workspaceId))
        runCurrent()

        manager.stateObservers.keys shouldContainExactly setOf(managed.id)
    }

    @Test
    fun `finished operations beyond the cap are evicted oldest first`() = runTest {
        val manager = create()
        val overflow = 5
        val total = OperationsManager.MAX_RETAINED_TERMINAL + overflow

        val submitted = (0 until total).map {
            val operation = FakeOperation(workspaceId)
            manager.submitManaged(operation) to operation
        }
        // Finished in reverse submission order, so the last submitted carry the oldest completedAt.
        // Evicting the last five submitted is right only if completedAt decides; evicting by
        // submission order would take the first five instead.
        submitted.reversed().forEachIndexed { index, (_, operation) ->
            operation.finish(epoch + index.seconds)
            runCurrent()
        }

        manager.operations.first().map { it.id } shouldContainExactly
            submitted.take(OperationsManager.MAX_RETAINED_TERMINAL).map { (managed, _) -> managed.id }
    }

    @Test
    fun `unfinished operations are never evicted`() = runTest {
        val manager = create()

        val unfinished = manager.submitManaged(FakeOperation(workspaceId)).id
        repeat(OperationsManager.MAX_RETAINED_TERMINAL + 5) { index ->
            val operation = FakeOperation(workspaceId)
            manager.submitManaged(operation)
            operation.finish(epoch + index.seconds)
            runCurrent()
        }

        val remaining = manager.operations.first()
        remaining.first().id shouldBe unfinished
        remaining.size shouldBe OperationsManager.MAX_RETAINED_TERMINAL + 1
    }

    @Test
    fun `eviction does not cost an operation its completion snapshot`() = runTest {
        val manager = create()
        val seen = recordCompletions(manager)
        val submitted = OperationsManager.MAX_RETAINED_TERMINAL + 5

        repeat(submitted) { index ->
            val operation = FakeOperation(workspaceId)
            manager.submitManaged(operation)
            operation.finish(epoch + index.seconds)
            runCurrent()
        }

        seen.size shouldBe submitted
        manager.operations.first().size shouldBe OperationsManager.MAX_RETAINED_TERMINAL
    }

    @Test
    fun `a workspace removal emits one completion and takes the operation's claim`() = runTest {
        val manager = create()
        val seen = recordCompletions(manager)
        val operation = FakeOperation(workspaceId)

        val managed = manager.submitManaged(operation)
        manager.removeWorkspace(workspaceId)
        runCurrent()

        seen.size shouldBe 1
        seen.single().state.error.shouldBeInstanceOf<CancellationException>()
        // The synthesis took the operation's one claim, which is what suppresses the real Completed
        // if it still reaches an observer that has not finished cancelling yet.
        managed.claimCompletionEmission() shouldBe false
    }

    @Test
    fun `a non-cancellable operation ignores a cancel request`() = runTest {
        val manager = create()
        val operation = FakeOperation(workspaceId, cancellable = false)

        val managed = manager.submitManaged(operation)
        manager.cancel(managed.id)
        runCurrent()

        managed.isUnfinished shouldBe true
        managed.canCancel shouldBe false
    }

    @Test
    fun `closeBlockers names the workspace of an unfinished REQUIRE_ORIGIN operation`() = runTest {
        val manager = create()
        val operation = FakeOperation(workspaceId, policy = Operation.Metadata.ClosePolicy.REQUIRE_ORIGIN)
        manager.submitManaged(operation)
        runCurrent()

        manager.closeBlockers(listOf(workspaceId)) shouldBe setOf(workspaceId)
        // An unrelated workspace is never named, even while the blocker is live
        manager.closeBlockers(listOf(Workspace.Id())).shouldBeEmpty()

        operation.finish(epoch)
        runCurrent()

        manager.closeBlockers(listOf(workspaceId)).shouldBeEmpty()
    }

    @Test
    fun `closeBlockers ignores an operation that closes with its origin`() = runTest {
        val manager = create()
        manager.submitManaged(FakeOperation(workspaceId))
        runCurrent()

        manager.closeBlockers(listOf(workspaceId)).shouldBeEmpty()
    }

    @Test
    fun `a workspace removal still cancels a REQUIRE_ORIGIN operation`() = runTest {
        val manager = create()
        val seen = recordCompletions(manager)
        val managed = manager.submitManaged(
            FakeOperation(workspaceId, policy = Operation.Metadata.ClosePolicy.REQUIRE_ORIGIN)
        )

        // The policy is enforced by whoever asks closeBlockers first; arriving here means the
        // submit-vs-close race happened, and then the operation goes down like any other.
        manager.removeWorkspace(workspaceId)
        runCurrent()

        seen.single().state.error.shouldBeInstanceOf<CancellationException>()
        manager.operations.first().map { it.id }.shouldBeEmpty()
        managed.claimCompletionEmission() shouldBe false
    }

    @Test
    fun `an operation grants its completion claim once`() = runTest {
        val managed = ManagedOperation(
            id = Operation.Id(),
            operation = FakeOperation(workspaceId),
            parentScope = backgroundScope,
        )

        managed.claimCompletionEmission() shouldBe true
        managed.claimCompletionEmission() shouldBe false
    }
}
