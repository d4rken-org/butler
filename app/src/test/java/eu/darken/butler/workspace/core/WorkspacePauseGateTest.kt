package eu.darken.butler.workspace.core

import android.os.Parcel
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import eu.darken.butler.workspace.core.undo.ClosedWorkspaceStash
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class WorkspacePauseGateTest : BaseTest() {

    private val idA = Workspace.Id()
    private val idB = Workspace.Id()

    private class FakeArguments(
        override val type: Workspace.Type,
    ) : Workspace.Arguments {
        override fun describeContents(): Int = 0
        override fun writeToParcel(dest: Parcel, flags: Int) = Unit
    }

    private class FakeChildArguments(
        override val type: Workspace.Type,
        override val callerWorkspaceId: Workspace.Id?,
    ) : Workspace.ArgumentsWithCaller {
        override val pausableAsChild: Boolean = true
        override fun describeContents(): Int = 0
        override fun writeToParcel(dest: Parcel, flags: Int) = Unit
    }

    private class FakeWorkspace(
        override val id: Workspace.Id,
        arguments: Workspace.Arguments,
    ) : Workspace<Workspace.Arguments> {
        override val type: Workspace.Type = arguments.type
        override val info = MutableStateFlow(
            Workspace.Info(
                id = id,
                type = type,
                title = "Fake $type".toCaString(),
                lifecycleState = Workspace.LifecycleState.Ready,
                callerWorkspaceId = (arguments as? Workspace.ArgumentsWithCaller)?.callerWorkspaceId,
                pausableAsChild = arguments.isPausableAsChild,
            )
        )

        override suspend fun createArguments(): Workspace.Arguments = FakeArguments(type)
    }

    private class FakeFactory : WorkspaceFactory<Workspace.Arguments> {
        override fun create(id: Workspace.Id, arguments: Workspace.Arguments): Workspace<Workspace.Arguments> =
            FakeWorkspace(id, arguments)

        override val argumentsSerializer: KSerializer<Workspace.Arguments>
            get() = throw NotImplementedError("serialize/deserialize are not used here")

        override fun serialize(json: Json, arguments: Workspace.Arguments): JsonElement = JsonNull

        override fun deserialize(json: Json, element: JsonElement): Workspace.Arguments =
            FakeArguments(Workspace.Type.EXPLORER)
    }

    private fun TestScope.createRepo(): WorkspaceRepo {
        val upgradeInfo = mockk<UpgradeRepo.Info>().apply { every { isPro } returns true }
        val upgradeRepo = mockk<UpgradeRepo>().apply { every { this@apply.upgradeInfo } returns flowOf(upgradeInfo) }
        val workspaceSettings = mockk<WorkspaceSettings>(relaxed = true).apply {
            every { layoutModePortrait.flow } returns flowOf(WorkspacePanelMode.AUTO)
            every { layoutModeLandscape.flow } returns flowOf(WorkspacePanelMode.AUTO)
        }
        return WorkspaceRepo(
            appScope = backgroundScope,
            factoryMap = Workspace.Type.entries.associateWith { FakeFactory() },
            workspaceSettings = workspaceSettings,
            operationsManager = mockk(relaxed = true),
            upgradeRepo = upgradeRepo,
            usageRepo = mockk(relaxed = true),
            closedStash = ClosedWorkspaceStash(backgroundScope),
        )
    }

    private suspend fun WorkspaceRepo.createTab(): Workspace.Id {
        val type = Workspace.Type.APPS
        val result = execute(WorkspaceAction.Create(type = type, arguments = FakeArguments(type)))
        return (result as WorkspaceAction.Create.Result.Success).newId
    }

    private suspend fun WorkspaceRepo.createChild(caller: Workspace.Id): Workspace.Id {
        val type = Workspace.Type.APP_DETAILS
        val result = execute(
            WorkspaceAction.Create(type = type, arguments = FakeChildArguments(type, caller))
        )
        return (result as WorkspaceAction.Create.Result.Success).newId
    }

    @Test
    fun `the same workspace id runs one lease at a time`() = runTest(UnconfinedTestDispatcher()) {
        val gate = WorkspacePauseGate()
        val order = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()

        val first = launch {
            gate.withLease(idA) {
                order += "first-enter"
                release.await()
                order += "first-exit"
            }
        }
        val second = launch {
            gate.withLease(idA) { order += "second" }
        }

        order shouldBe listOf("first-enter")

        release.complete(Unit)
        first.join()
        second.join()

        order shouldBe listOf("first-enter", "first-exit", "second")
    }

    @Test
    fun `a lease on one workspace does not block another`() = runTest(UnconfinedTestDispatcher()) {
        val gate = WorkspacePauseGate()
        val order = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()

        val first = launch {
            gate.withLease(idA) {
                order += "a-enter"
                release.await()
                order += "a-exit"
            }
        }
        val second = launch {
            gate.withLease(idB) { order += "b" }
        }

        order shouldBe listOf("a-enter", "b")

        release.complete(Unit)
        first.join()
        second.join()
    }

    @Test
    fun `a failing lease is still released`() = runTest(UnconfinedTestDispatcher()) {
        val gate = WorkspacePauseGate()
        var ran = false

        try {
            gate.withLease(idA) { throw IllegalStateException("boom") }
        } catch (_: IllegalStateException) {
        }

        gate.withLease(idA) { ran = true }
        ran shouldBe true
    }

    @Test
    fun `a stack pause and a capture of one of its members serialise`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val rootId = repo.createTab()
        val childId = repo.createChild(caller = rootId)
        val gate = WorkspacePauseGate()
        val order = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()

        // Pausing leases the unit's root, because that is what it releases
        val pauseSide = launch {
            gate.withLease(repo.peekOwnershipRoot(rootId)) {
                order += "pause-enter"
                release.await()
                order += "pause-exit"
            }
        }
        // A preview capture of the modal child resolves to the very same key
        val captureSide = launch {
            gate.withLease(repo.peekOwnershipRoot(childId)) { order += "capture-child" }
        }

        order shouldBe listOf("pause-enter")

        release.complete(Unit)
        pauseSide.join()
        captureSide.join()

        order shouldBe listOf("pause-enter", "pause-exit", "capture-child")
    }

    @Test
    fun `a capture in another stack is not blocked by a stack pause`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val firstRootId = repo.createTab()
        val secondRootId = repo.createTab()
        val secondChildId = repo.createChild(caller = secondRootId)
        val gate = WorkspacePauseGate()
        val order = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()

        val pauseSide = launch {
            gate.withLease(repo.peekOwnershipRoot(firstRootId)) {
                order += "pause-enter"
                release.await()
                order += "pause-exit"
            }
        }
        val captureSide = launch {
            gate.withLease(repo.peekOwnershipRoot(secondChildId)) { order += "capture-other-stack" }
        }

        order shouldBe listOf("pause-enter", "capture-other-stack")

        release.complete(Unit)
        pauseSide.join()
        captureSide.join()
    }
}
