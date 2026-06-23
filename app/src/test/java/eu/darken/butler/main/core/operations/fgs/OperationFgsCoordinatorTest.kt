package eu.darken.butler.main.core.operations.fgs

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.core.operations.CompletedOperationSnapshot
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant

class OperationFgsCoordinatorTest : BaseTest() {

    private val t0 = Instant.fromEpochMilliseconds(0)

    private lateinit var testScope: TestScope
    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager
    private lateinit var notifications: OperationNotifications
    private lateinit var permissionGate: NotificationPermissionGate
    private lateinit var operationsManager: OperationsManager
    private lateinit var opsFlow: MutableStateFlow<List<ManagedOperation>>
    private lateinit var completed: MutableSharedFlow<CompletedOperationSnapshot>
    private lateinit var coordinator: OperationFgsCoordinator

    @BeforeEach
    fun setup() {
        testScope = TestScope(UnconfinedTestDispatcher())
        context = mockk(relaxed = true)
        notificationManager = mockk(relaxed = true) {
            every { activeNotifications } returns emptyArray()
        }
        notifications = mockk(relaxed = true) {
            every { buildSummary(any()) } returns mockk<Notification>(relaxed = true)
            every { buildProgress(any(), any(), any()) } returns mockk<Notification>(relaxed = true)
            every { buildAttention(any(), any()) } returns mockk<Notification>(relaxed = true)
            every { buildFailure(any(), any()) } returns mockk<Notification>(relaxed = true)
        }
        permissionGate = mockk(relaxed = true)
        opsFlow = MutableStateFlow(emptyList())
        completed = MutableSharedFlow(extraBufferCapacity = 8)
        operationsManager = mockk {
            every { operations } returns opsFlow
            every { completedOperations } returns completed
        }
    }

    private fun startCoordinator() {
        coordinator = OperationFgsCoordinator(
            context = context,
            appScope = testScope,
            operationsManager = operationsManager,
            notifications = notifications,
            notificationManager = notificationManager,
            permissionGate = permissionGate,
        )
        coordinator.start()
    }

    private fun managedOp(
        state: Operation.State,
        id: Operation.Id = Operation.Id(),
        cancellable: Boolean = true,
    ): ManagedOperation = mockk {
        every { this@mockk.id } returns id
        every { this@mockk.state } returns MutableStateFlow(state)
        every { canCancel } returns cancellable
    }

    private fun completedState(error: Throwable? = null) = object : Operation.State.Completed {
        override val startedAt = t0
        override val completedAt = t0
        override val summary = "done".toCaString()
        override val report: Operation.Report? = null
        override val error: Throwable? = error
    }

    @Test
    fun `no foreground service while the app is on screen`() {
        startCoordinator()
        opsFlow.value = listOf(managedOp(Operation.State.Queued(t0)))
        testScope.advanceUntilIdle()

        verify(exactly = 0) { context.startForegroundService(any()) }
    }

    @Test
    fun `backgrounding with an active operation starts the foreground service`() {
        startCoordinator()
        opsFlow.value = listOf(managedOp(Operation.State.Queued(t0)))
        testScope.advanceUntilIdle()

        coordinator.onAppBackgrounded()
        testScope.advanceUntilIdle()

        verify(atLeast = 1) { context.startForegroundService(any()) }
    }

    @Test
    fun `completed operations lingering in the list never start the service`() {
        startCoordinator()
        opsFlow.value = listOf(managedOp(completedState()), managedOp(completedState()))
        coordinator.onAppBackgrounded()
        testScope.advanceUntilIdle()

        verify(exactly = 0) { context.startForegroundService(any()) }
    }

    @Test
    fun `returning to the foreground stops the service`() {
        startCoordinator()
        opsFlow.value = listOf(managedOp(Operation.State.Queued(t0)))
        testScope.advanceUntilIdle()
        coordinator.onAppBackgrounded()
        testScope.advanceUntilIdle()
        verify(atLeast = 1) { context.startForegroundService(any()) }

        coordinator.onAppForegrounded()
        testScope.advanceUntilIdle()
        verify { context.stopService(any()) }
    }

    @Test
    fun `service is stopped after the last operation completes`() {
        startCoordinator()
        opsFlow.value = listOf(managedOp(Operation.State.Queued(t0)))
        testScope.advanceUntilIdle()
        coordinator.onAppBackgrounded()
        testScope.advanceUntilIdle()
        opsFlow.value = listOf(managedOp(completedState()))
        testScope.advanceUntilIdle() // includes the stop debounce delay

        verify { context.stopService(any()) }
    }

    @Test
    fun `a failed operation in the background posts a failure notification`() {
        startCoordinator()
        coordinator.onAppBackgrounded()
        testScope.advanceUntilIdle()
        val snapshot = CompletedOperationSnapshot(
            id = Operation.Id(),
            metadata = mockk(relaxed = true),
            state = completedState(error = RuntimeException("boom")),
        )
        completed.tryEmit(snapshot)
        testScope.advanceUntilIdle()

        verify { notifications.buildFailure(any(), snapshot) }
    }

    @Test
    fun `a failed operation in the foreground does not post a notification`() {
        startCoordinator() // foreground by default
        completed.tryEmit(
            CompletedOperationSnapshot(
                id = Operation.Id(),
                metadata = mockk(relaxed = true),
                state = completedState(error = RuntimeException("boom")),
            )
        )
        testScope.advanceUntilIdle()

        verify(exactly = 0) { notifications.buildFailure(any(), any()) }
    }

    @Test
    fun `a cancelled operation does not post a failure notification`() {
        startCoordinator()
        coordinator.onAppBackgrounded()
        testScope.advanceUntilIdle()
        completed.tryEmit(
            CompletedOperationSnapshot(
                id = Operation.Id(),
                metadata = mockk(relaxed = true),
                state = completedState(error = kotlin.coroutines.cancellation.CancellationException("cancel")),
            )
        )
        testScope.advanceUntilIdle()

        verify(exactly = 0) { notifications.buildFailure(any(), any()) }
    }

    @Test
    fun `clearing a failed operation dismisses its failure notification`() {
        val failureId = slot<Int>()
        every { notifications.buildFailure(capture(failureId), any()) } returns mockk(relaxed = true)

        startCoordinator()
        coordinator.onAppBackgrounded()
        testScope.advanceUntilIdle()

        val opId = Operation.Id()
        // The failed op lingers in OperationsManager (completed but not yet cleared).
        opsFlow.value = listOf(managedOp(completedState(error = RuntimeException("boom")), id = opId))
        completed.tryEmit(
            CompletedOperationSnapshot(
                id = opId,
                metadata = mockk(relaxed = true),
                state = completedState(error = RuntimeException("boom")),
            )
        )
        testScope.advanceUntilIdle()
        val id = failureId.captured

        // User taps "Clear completed" → the op leaves OperationsManager.
        opsFlow.value = emptyList()
        testScope.advanceUntilIdle()

        verify { notificationManager.cancel(id) }
    }
}
