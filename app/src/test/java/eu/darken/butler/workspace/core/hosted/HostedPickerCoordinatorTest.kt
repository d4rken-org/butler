package eu.darken.butler.workspace.core.hosted

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.workspace.contracts.explorer.PickerConfig
import eu.darken.butler.workspace.contracts.picker.HostedPickerRequests
import eu.darken.butler.workspace.contracts.picker.HostedPickerRequests.Outcome
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.hosted.HostedPickerCoordinator.Phase
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Duration.Companion.milliseconds

class HostedPickerCoordinatorTest : BaseTest() {

    private val remote = FakeHostingRemote()
    private val folder = LocalPath.build("/storage/emulated/0/Download")

    private fun TestScope.coordinator() = HostedPickerCoordinator(appScope = backgroundScope, workspaceRemote = remote)

    private suspend fun HostedPickerCoordinator.open(): Pair<HostedPickerRequests.Ticket, Workspace.Id> {
        val ticket = register(HostedPickerRequests.Request(startPath = folder))
        val host = attach(ticket.token).first { it?.phase == Phase.OPEN }!!
        return ticket to host.pickerId
    }

    @Test
    fun `the first attach launches one hosted picker answering to the request`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator()
        val (ticket, pickerId) = coordinator.open()
        coordinator.attach(ticket.token).first()

        remote.actions.filterIsInstance<WorkspaceAction.Create>() shouldHaveSize 1
        remote.createdArguments.apply {
            isHostedByNavigation shouldBe true
            startPath shouldBe folder
            selection shouldBe PickerConfig.Selection.DirectorySingle
            requireWritable shouldBe false
        }
        remote.infos.value.single().id shouldBe pickerId
    }

    @Test
    fun `a pick survives the picker closing and is delivered even when nobody waited yet`() =
        runTest(UnconfinedTestDispatcher()) {
            val coordinator = coordinator()
            val (ticket, pickerId) = coordinator.open()
            val hostId = remote.createdArguments.callerWorkspaceId

            remote.eventFlow.emit(WorkspaceEvent.PickerResult(pickerId, hostId, listOf(folder)))
            remote.execute(WorkspaceAction.Close(pickerId))

            coordinator.attach(ticket.token).first()?.phase shouldBe Phase.FINISHED
            coordinator.awaitOutcome(ticket) shouldBe Outcome.Picked(listOf(folder))
            // Delivered and finished: the destination now sees the request gone
            coordinator.attach(ticket.token).first() shouldBe null
        }

    @Test
    fun `a picker the hosted one opened neither answers nor ends the request`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator()
        val (ticket, pickerId) = coordinator.open()
        val keyPicker = Workspace.Id()
        val keyFile = LocalPath.build("/storage/emulated/0/id_ed25519")

        remote.eventFlow.emit(WorkspaceEvent.PickerResult(keyPicker, pickerId, listOf(keyFile)))
        remote.eventFlow.emit(WorkspaceEvent.ResultCancelled(keyPicker, pickerId))
        remote.eventFlow.emit(WorkspaceEvent.Closed(workspaceId = keyPicker, callerWorkspaceId = pickerId))

        coordinator.attach(ticket.token).first()?.phase shouldBe Phase.OPEN
        val waiting = async { coordinator.awaitOutcome(ticket) }
        waiting.isCompleted shouldBe false

        remote.eventFlow.emit(WorkspaceEvent.PickerResult(pickerId, remote.createdArguments.callerWorkspaceId, listOf(folder)))
        waiting.await() shouldBe Outcome.Picked(listOf(folder))
    }

    @Test
    fun `the outcome is only handed out once the picker is gone`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator()
        val (ticket, pickerId) = coordinator.open()
        val waiting = async { coordinator.awaitOutcome(ticket) }

        remote.eventFlow.emit(WorkspaceEvent.PickerResult(pickerId, remote.createdArguments.callerWorkspaceId, listOf(folder)))
        waiting.isCompleted shouldBe false

        remote.execute(WorkspaceAction.Close(pickerId))
        waiting.await() shouldBe Outcome.Picked(listOf(folder))
    }

    @Test
    fun `a result naming another host is ignored`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator()
        val (ticket, pickerId) = coordinator.open()
        val waiting = async { coordinator.awaitOutcome(ticket) }

        remote.eventFlow.emit(WorkspaceEvent.PickerResult(pickerId, Workspace.Id(), listOf(folder)))

        waiting.isCompleted shouldBe false
        waiting.cancel()
    }

    @Test
    fun `cancelling the picker cancels the request`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator()
        val (ticket, pickerId) = coordinator.open()

        remote.eventFlow.emit(WorkspaceEvent.ResultCancelled(pickerId, remote.createdArguments.callerWorkspaceId))
        remote.execute(WorkspaceAction.Close(pickerId))

        coordinator.awaitOutcome(ticket) shouldBe Outcome.Cancelled
    }

    @Test
    fun `closing during the create closes the picker once it exists`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator()
        val gate = CompletableDeferred<Unit>().also { remote.createGate = it }
        val ticket = coordinator.register(HostedPickerRequests.Request())
        coordinator.attach(ticket.token).first()?.phase shouldBe Phase.LAUNCHING

        coordinator.close(ticket.token)
        remote.closes shouldHaveSize 0
        gate.complete(Unit)

        remote.closes shouldHaveSize 1
        remote.infos.value shouldBe emptyList()
        coordinator.awaitOutcome(ticket) shouldBe Outcome.Cancelled
    }

    @Test
    fun `a request closed before any destination attached never creates a picker`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator()
        val ticket = coordinator.register(HostedPickerRequests.Request())

        coordinator.close(ticket.token)

        coordinator.awaitOutcome(ticket) shouldBe Outcome.Cancelled
        coordinator.attach(ticket.token).first() shouldBe null
        remote.actions shouldBe emptyList()
    }

    @Test
    fun `giving up on the outcome closes the picker`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator()
        val (ticket, pickerId) = coordinator.open()

        val waiting = async { coordinator.awaitOutcome(ticket) }
        waiting.cancel()

        remote.closes.single().id shouldBe pickerId
        remote.infos.value shouldBe emptyList()
        coordinator.attach(ticket.token).first() shouldBe null
    }

    @Test
    fun `close all ends an open request`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator()
        val (ticket, _) = coordinator.open()

        remote.infos.value = emptyList()
        remote.eventFlow.emit(WorkspaceEvent.AllClosed)

        coordinator.awaitOutcome(ticket) shouldBe Outcome.Cancelled
    }

    @Test
    fun `a destination restored after process death finds no request`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator()
        val stale = HostedPickerRequests.Ticket(Workspace.Id().longTag)

        coordinator.attach(stale.token).first() shouldBe null
        coordinator.awaitOutcome(stale) shouldBe Outcome.Cancelled
        remote.actions shouldBe emptyList()
    }

    @Test
    fun `a refused close keeps being retried until the picker is gone`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator()
        val (ticket, pickerId) = coordinator.open()
        remote.refuseClose = true

        coordinator.close(ticket.token)
        remote.closes shouldHaveSize 1
        advanceTimeBy(HostedPickerCoordinator.CLOSE_RETRY + 1.milliseconds)
        remote.closes shouldHaveSize 2

        remote.refuseClose = false
        advanceTimeBy(HostedPickerCoordinator.CLOSE_RETRY + 1.milliseconds)

        remote.closes shouldHaveSize 3
        remote.infos.value.none { it.id == pickerId } shouldBe true
        coordinator.awaitOutcome(ticket).shouldBeInstanceOf<Outcome.Cancelled>()
    }

    @Test
    fun `a picker whose own close after picking was refused still goes away`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator()
        val (ticket, pickerId) = coordinator.open()
        remote.refuseClose = true

        // Result first, then a refused close
        remote.eventFlow.emit(WorkspaceEvent.PickerResult(pickerId, remote.createdArguments.callerWorkspaceId, listOf(folder)))
        remote.execute(WorkspaceAction.Close(pickerId))
        remote.refuseClose = false
        advanceTimeBy(HostedPickerCoordinator.CLOSE_RETRY + 1.milliseconds)

        remote.infos.value.none { it.id == pickerId } shouldBe true
        coordinator.awaitOutcome(ticket) shouldBe Outcome.Picked(listOf(folder))
    }

    @Test
    fun `close all right after the existence check still ends the request`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator()
        val ticket = coordinator.register(HostedPickerRequests.Request())
        remote.afterNextPeek = {
            remote.infos.value = emptyList()
            remote.eventFlow.emit(WorkspaceEvent.AllClosed)
        }

        coordinator.attach(ticket.token).first()

        coordinator.awaitOutcome(ticket) shouldBe Outcome.Cancelled
        coordinator.attach(ticket.token).first() shouldBe null
    }
}
