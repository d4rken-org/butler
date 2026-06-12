package eu.darken.butler.workspace.core

import android.os.Parcel
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.core.operations.OperationsManager
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class WorkspaceRepoTest : BaseTest() {

    private class FakeArguments(
        override val type: Workspace.Type,
    ) : Workspace.Arguments {
        override fun describeContents(): Int = 0
        override fun writeToParcel(dest: Parcel, flags: Int) = Unit
    }

    private class FakePickerArguments(
        override val type: Workspace.Type,
        override val callerWorkspaceId: Workspace.Id?,
    ) : Workspace.ArgumentsForResult {
        override fun describeContents(): Int = 0
        override fun writeToParcel(dest: Parcel, flags: Int) = Unit
    }

    private class FakeWorkspace(
        override val id: Workspace.Id,
        private val arguments: Workspace.Arguments,
    ) : Workspace<Workspace.Arguments> {
        override val type: Workspace.Type = arguments.type
        var released = false
            private set

        override val info = MutableStateFlow(
            Workspace.Info(
                id = id,
                type = type,
                title = "Fake $type".toCaString(),
                callerWorkspaceId = (arguments as? Workspace.ArgumentsWithCaller)?.callerWorkspaceId,
                modalPresentation = (arguments as? Workspace.ArgumentsWithCaller)?.modalPresentation
                    ?: Workspace.ModalPresentationMode.PANE_LOCAL,
            )
        )

        override suspend fun createArguments(): Workspace.Arguments = arguments

        override suspend fun release() {
            released = true
        }
    }

    private val createdWorkspaces = mutableListOf<FakeWorkspace>()

    private inner class FakeFactory : WorkspaceFactory<Workspace.Arguments> {
        override fun create(id: Workspace.Id, arguments: Workspace.Arguments): Workspace<Workspace.Arguments> =
            FakeWorkspace(id, arguments).also { createdWorkspaces += it }

        override fun serialize(json: Json, arguments: Workspace.Arguments): JsonElement = JsonNull

        override fun deserialize(json: Json, element: JsonElement): Workspace.Arguments =
            FakeArguments(Workspace.Type.EXPLORER)
    }

    private val operationsManager: OperationsManager = mockk(relaxed = true)

    private fun TestScope.createRepo(isPro: Boolean = false): WorkspaceRepo {
        val upgradeInfo = mockk<UpgradeRepo.Info>().apply {
            every { isUpgraded } returns isPro
        }
        val upgradeRepo = mockk<UpgradeRepo>().apply {
            every { this@apply.upgradeInfo } returns flowOf(upgradeInfo)
        }
        return WorkspaceRepo(
            appScope = backgroundScope,
            factoryMap = Workspace.Type.entries.associateWith { FakeFactory() },
            workspaceSettings = mockk(relaxed = true),
            operationsManager = operationsManager,
            upgradeRepo = upgradeRepo,
        )
    }

    private suspend fun WorkspaceRepo.createTab(
        type: Workspace.Type = Workspace.Type.EXPLORER,
    ): Workspace.Id {
        val result = execute(WorkspaceAction.Create(type = type, arguments = FakeArguments(type)))
        return (result as WorkspaceAction.Create.Result.Success).newId
    }

    private suspend fun WorkspaceRepo.createSubWorkspace(
        caller: Workspace.Id,
        type: Workspace.Type = Workspace.Type.EXPLORER,
    ): Workspace.Id {
        val result = execute(
            WorkspaceAction.Create(type = type, arguments = FakePickerArguments(type, caller))
        )
        return (result as WorkspaceAction.Create.Result.Success).newId
    }

    private fun fake(id: Workspace.Id): FakeWorkspace = createdWorkspaces.single { it.id == id }

    @Test
    fun `closing a parent closes its children`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val parentId = repo.createTab()
        val childId = repo.createSubWorkspace(caller = parentId)

        repo.execute(WorkspaceAction.Close(parentId))

        fake(parentId).released shouldBe true
        fake(childId).released shouldBe true
        repo.retrieve(childId).first() shouldBe null
        coVerify { operationsManager.removeWorkspace(childId) }
    }

    @Test
    fun `closing a parent closes grandchildren recursively`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val parentId = repo.createTab()
        val childId = repo.createSubWorkspace(caller = parentId)
        val grandchildId = repo.createSubWorkspace(caller = childId)

        repo.execute(WorkspaceAction.Close(parentId))

        fake(childId).released shouldBe true
        fake(grandchildId).released shouldBe true
        repo.retrieve(grandchildId).first() shouldBe null
    }

    @Test
    fun `closed event carries callerWorkspaceId`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val events = mutableListOf<WorkspaceEvent>()
        repo.events.onEach { events += it }.launchIn(backgroundScope)

        val parentId = repo.createTab()
        val childId = repo.createSubWorkspace(caller = parentId)

        repo.execute(WorkspaceAction.Close(childId))

        val closed = events.filterIsInstance<WorkspaceEvent.Closed>().single()
        closed.workspaceId shouldBe childId
        closed.callerWorkspaceId shouldBe parentId
    }

    @Test
    fun `sub-workspaces do not count toward the free tier limit`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        repeat(WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT) { repo.createTab() }

        val overLimit = repo.execute(
            WorkspaceAction.Create(type = Workspace.Type.EXPLORER, arguments = FakeArguments(Workspace.Type.EXPLORER))
        )
        overLimit.shouldBeInstanceOf<WorkspaceAction.Create.Result.LimitReached>()

        val parentId = createdWorkspaces.first().id
        val subResult = repo.execute(
            WorkspaceAction.Create(
                type = Workspace.Type.EXPLORER,
                arguments = FakePickerArguments(Workspace.Type.EXPLORER, parentId),
            )
        )
        subResult.shouldBeInstanceOf<WorkspaceAction.Create.Result.Success>()
    }

    @Test
    fun `pro users have no workspace limit`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = true)
        repeat(WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT + 2) { repo.createTab() }
        createdWorkspaces shouldHaveSize WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT + 2
    }

    @Test
    fun `singleton types return AlreadyOpen instead of duplicating`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val firstId = repo.createTab(type = Workspace.Type.DEVELOPER)

        val second = repo.execute(
            WorkspaceAction.Create(
                type = Workspace.Type.DEVELOPER,
                arguments = FakeArguments(Workspace.Type.DEVELOPER),
            )
        )

        second.shouldBeInstanceOf<WorkspaceAction.Create.Result.AlreadyOpen>()
        second.existingId shouldBe firstId
    }

    @Test
    fun `replacing a workspace releases the replaced instance`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val originalId = repo.createTab()

        val result = repo.execute(
            WorkspaceAction.Create(
                type = Workspace.Type.SEARCHER,
                arguments = FakeArguments(Workspace.Type.SEARCHER),
                replace = originalId,
            )
        )

        result.shouldBeInstanceOf<WorkspaceAction.Create.Result.Success>()
        fake(originalId).released shouldBe true
        coVerify { operationsManager.removeWorkspace(originalId) }
        repo.retrieve(originalId).first() shouldBe null
    }

    @Test
    fun `replacing a workspace closes its orphaned sub-workspaces`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val originalId = repo.createTab()
        val childId = repo.createSubWorkspace(caller = originalId)

        repo.execute(
            WorkspaceAction.Create(
                type = Workspace.Type.SEARCHER,
                arguments = FakeArguments(Workspace.Type.SEARCHER),
                replace = originalId,
            )
        )

        fake(childId).released shouldBe true
        repo.retrieve(childId).first() shouldBe null
    }
}
