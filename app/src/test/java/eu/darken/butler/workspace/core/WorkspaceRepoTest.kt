package eu.darken.butler.workspace.core

import android.os.Parcel
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.core.operations.OperationsManager
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import kotlinx.serialization.KSerializer
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

        override val argumentsSerializer: KSerializer<Workspace.Arguments>
            get() = throw NotImplementedError("serialize/deserialize are overridden directly")

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

    private fun createReq(
        type: Workspace.Type,
        id: Workspace.Id? = null,
    ): WorkspaceAction.Create = WorkspaceAction.Create(type = type, arguments = FakeArguments(type), id = id)

    private suspend fun WorkspaceRepo.createBatch(
        vararg requests: WorkspaceAction.Create,
    ): WorkspaceAction.CreateBatch.Result.Success =
        execute(WorkspaceAction.CreateBatch(requests = requests.toList())) as WorkspaceAction.CreateBatch.Result.Success

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

    @Test
    fun `quota-exempt types do not count toward the free tier limit`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        // An exempt singleton open alongside a full set of normal tabs must not consume a slot.
        repo.createTab(type = Workspace.Type.DEVELOPER)
        repeat(WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT) {
            repo.execute(createReq(Workspace.Type.EXPLORER))
                .shouldBeInstanceOf<WorkspaceAction.Create.Result.Success>()
        }

        repo.execute(createReq(Workspace.Type.EXPLORER))
            .shouldBeInstanceOf<WorkspaceAction.Create.Result.LimitReached>()
    }

    @Test
    fun `quota-exempt types can be created even at the limit`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        repeat(WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT) { repo.createTab() }

        repo.execute(createReq(Workspace.Type.DEVELOPER))
            .shouldBeInstanceOf<WorkspaceAction.Create.Result.Success>()
        repo.execute(createReq(Workspace.Type.BUG_REPORT))
            .shouldBeInstanceOf<WorkspaceAction.Create.Result.Success>()
    }

    @Test
    fun `batch preserves request order for mixed exempt and counted creates`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)

        repo.createBatch(
            createReq(Workspace.Type.EXPLORER),
            createReq(Workspace.Type.DEVELOPER),
            createReq(Workspace.Type.SEARCHER),
        )

        createdWorkspaces.map { it.type } shouldBe listOf(
            Workspace.Type.EXPLORER,
            Workspace.Type.DEVELOPER,
            Workspace.Type.SEARCHER,
        )
    }

    @Test
    fun `batch at the limit creates exempt types and skips counted ones`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        repeat(WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT) { repo.createTab() }

        val result = repo.createBatch(
            createReq(Workspace.Type.DEVELOPER),
            createReq(Workspace.Type.EXPLORER),
        )

        result.skippedCount shouldBe 1
        createdWorkspaces.count { it.type == Workspace.Type.DEVELOPER } shouldBe 1
        createdWorkspaces.count { it.type == Workspace.Type.EXPLORER } shouldBe WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT
    }

    @Test
    fun `counted-only batch at the limit emits no completion event`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        val events = mutableListOf<WorkspaceEvent>()
        repo.events.onEach { events += it }.launchIn(backgroundScope)
        repeat(WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT) { repo.createTab() }

        val result = repo.createBatch(
            createReq(Workspace.Type.EXPLORER),
            createReq(Workspace.Type.EXPLORER),
        )

        result.skippedCount shouldBe 2
        events.filterIsInstance<WorkspaceEvent.BatchCreationCompleted>() shouldHaveSize 0
    }

    @Test
    fun `batch sub-workspace requests bypass the limit`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        repeat(WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT) { repo.createTab() }
        val parentId = createdWorkspaces.first().id

        val result = repo.createBatch(
            WorkspaceAction.Create(
                type = Workspace.Type.EXPLORER,
                arguments = FakePickerArguments(Workspace.Type.EXPLORER, parentId),
            ),
        )

        result.skippedCount shouldBe 0
        result.results.values.single()
            .shouldBeInstanceOf<WorkspaceAction.CreateBatch.CreationResult.Success>()
    }

    @Test
    fun `duplicate singleton in one batch creates once and resolves duplicates to AlreadyOpen`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo(isPro = false)
            val firstId = Workspace.Id()
            val secondId = Workspace.Id()

            val result = repo.createBatch(
                createReq(Workspace.Type.DEVELOPER, id = firstId),
                createReq(Workspace.Type.DEVELOPER, id = secondId),
            )

            createdWorkspaces.count { it.type == Workspace.Type.DEVELOPER } shouldBe 1
            val resultValues = result.results.values.toList()
            resultValues.count { it is WorkspaceAction.CreateBatch.CreationResult.Success } shouldBe 1
            resultValues.count { it is WorkspaceAction.CreateBatch.CreationResult.AlreadyOpen } shouldBe 1
        }

    @Test
    fun `identical singleton requests in one batch collapse to a single Success`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo(isPro = false)
            // Same Create instance twice → one map key; the created instance must stay Success and not
            // be overwritten by the deferred-duplicate AlreadyOpen resolution.
            val request = createReq(Workspace.Type.DEVELOPER)

            val result = repo.createBatch(request, request)

            createdWorkspaces.count { it.type == Workspace.Type.DEVELOPER } shouldBe 1
            result.results.size shouldBe 1
            result.results.values.single()
                .shouldBeInstanceOf<WorkspaceAction.CreateBatch.CreationResult.Success>()
        }

    @Test
    fun `batch of only already-open singletons emits no completion event`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        val events = mutableListOf<WorkspaceEvent>()
        repo.events.onEach { events += it }.launchIn(backgroundScope)
        val existingId = repo.createTab(type = Workspace.Type.DEVELOPER)

        val result = repo.createBatch(createReq(Workspace.Type.DEVELOPER))

        result.results.values.single()
            .shouldBeInstanceOf<WorkspaceAction.CreateBatch.CreationResult.AlreadyOpen>()
        (result.results.values.single() as WorkspaceAction.CreateBatch.CreationResult.AlreadyOpen)
            .existingId shouldBe existingId
        events.filterIsInstance<WorkspaceEvent.BatchCreationCompleted>() shouldHaveSize 0
    }

    @Test
    fun `confirmed batch re-applies the limit against tabs opened while awaiting confirmation`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo(isPro = false)
            // Empty repo: a batch of exactly the limit trips confirmation (threshold == limit).
            val requests = (1..WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT).map { createReq(Workspace.Type.EXPLORER) }
            repo.execute(WorkspaceAction.CreateBatch(requests = requests))
                .shouldBeInstanceOf<WorkspaceAction.CreateBatch.Result.AwaitingConfirmation>()

            // The user opens two more tabs before confirming.
            repo.createTab()
            repo.createTab()

            val confirmationId = repo.pendingConfirmations.first().keys.single()
            repo.resolveConfirmation(confirmationId, confirmed = true)

            // Two manual tabs + (limit - 2) from the batch == limit; never the pre-fix total of limit + 2.
            createdWorkspaces shouldHaveSize WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT
        }

    private fun markDirty(id: Workspace.Id) {
        val ws = fake(id)
        ws.info.value = ws.info.value.copy(hasUnsavedChanges = true)
    }

    private fun Map<String, PendingWorkspaceConfirmation>.closeConfirmationsFor(id: Workspace.Id): Int =
        values.count {
            val data = it.data
            data is PendingWorkspaceConfirmation.ConfirmationData.WorkspaceCloseConfirmation &&
                data.workspaceId == id
        }

    @Test
    fun `closing a dirty workspace queues a confirmation instead of closing`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val id = repo.createTab()
            markDirty(id)

            repo.execute(WorkspaceAction.Close(id))

            fake(id).released shouldBe false
            repo.retrieve(id).first() shouldNotBe null
            repo.pendingConfirmations.first().closeConfirmationsFor(id) shouldBe 1
        }

    @Test
    fun `closing a clean workspace closes immediately without confirmation`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val id = repo.createTab()

            repo.execute(WorkspaceAction.Close(id))

            fake(id).released shouldBe true
            repo.retrieve(id).first() shouldBe null
            repo.pendingConfirmations.first() shouldBe emptyMap()
        }

    @Test
    fun `repeated close on a dirty workspace queues only one confirmation`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val id = repo.createTab()
            markDirty(id)

            repo.execute(WorkspaceAction.Close(id))
            repo.execute(WorkspaceAction.Close(id))
            repo.execute(WorkspaceAction.Close(id))

            repo.pendingConfirmations.first().closeConfirmationsFor(id) shouldBe 1
        }

    @Test
    fun `batch honors an explicit workspace id`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val explicitId = Workspace.Id()

        val result = repo.createBatch(createReq(Workspace.Type.EXPLORER, id = explicitId))

        val success = result.results.values.single()
            .shouldBeInstanceOf<WorkspaceAction.CreateBatch.CreationResult.Success>()
        success.workspaceId shouldBe explicitId
        createdWorkspaces.single().id shouldBe explicitId
    }

    @Test
    fun `batch rejects a colliding explicit id without dropping the first create`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val sharedId = Workspace.Id()

            val result = repo.createBatch(
                createReq(Workspace.Type.EXPLORER, id = sharedId),
                createReq(Workspace.Type.SEARCHER, id = sharedId),
            )

            // First request creates the tab; the colliding second fails instead of duplicating the id.
            createdWorkspaces.single().id shouldBe sharedId
            val values = result.results.values.toList()
            values.count { it is WorkspaceAction.CreateBatch.CreationResult.Success } shouldBe 1
            values.count { it is WorkspaceAction.CreateBatch.CreationResult.Failure } shouldBe 1
        }
}
