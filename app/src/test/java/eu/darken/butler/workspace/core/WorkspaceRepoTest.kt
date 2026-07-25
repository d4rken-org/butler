package eu.darken.butler.workspace.core

import android.content.Context
import android.os.Parcel
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import eu.darken.butler.workspace.core.operations.OperationsManager
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

    private data class FakeContentArguments(
        override val type: Workspace.Type,
        override val contentPath: APath<*>?,
    ) : Workspace.ArgumentsWithContentPath {
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
                contentPath = (arguments as? Workspace.ArgumentsWithContentPath)?.contentPath,
            )
        )

        override suspend fun createArguments(): Workspace.Arguments = arguments

        override suspend fun release() {
            released = true
        }
    }

    private val createdWorkspaces = mutableListOf<FakeWorkspace>()

    /** When set, the next [FakeFactory.create] throws it instead of creating a workspace. */
    private var nextCreateFailure: Exception? = null

    /** When set, the next [FakeFactory.deriveDisplay] throws it instead of deriving an identity. */
    private var nextDeriveFailure: Exception? = null

    /** Identity every [FakeFactory] derives; null models a type without a derivation. */
    private var derivedDisplay: WorkspaceDisplay? = null

    private inner class FakeFactory : WorkspaceFactory<Workspace.Arguments> {
        override fun create(id: Workspace.Id, arguments: Workspace.Arguments): Workspace<Workspace.Arguments> {
            nextCreateFailure?.let {
                nextCreateFailure = null
                throw it
            }
            return FakeWorkspace(id, arguments).also { createdWorkspaces += it }
        }

        override fun deriveDisplay(arguments: Workspace.Arguments): WorkspaceDisplay? {
            nextDeriveFailure?.let {
                nextDeriveFailure = null
                throw it
            }
            return derivedDisplay
        }

        override val argumentsSerializer: KSerializer<Workspace.Arguments>
            get() = throw NotImplementedError("serialize/deserialize are overridden directly")

        override fun serialize(json: Json, arguments: Workspace.Arguments): JsonElement = JsonNull

        override fun deserialize(json: Json, element: JsonElement): Workspace.Arguments =
            FakeArguments(Workspace.Type.EXPLORER)
    }

    private val operationsManager: OperationsManager = mockk(relaxed = true)

    // Resource ids resolve to a stable stand-in so CaStrings can be compared by resolved value
    // (CaString has no structural equality)
    private val context: Context = mockk<Context>().apply {
        every { getString(any()) } answers { "res-${firstArg<Int>()}" }
    }

    private fun TestScope.createRepo(isPro: Boolean = false): WorkspaceRepo {
        val upgradeInfo = mockk<UpgradeRepo.Info>().apply {
            every { isUpgraded } returns isPro
        }
        val upgradeRepo = mockk<UpgradeRepo>().apply {
            every { this@apply.upgradeInfo } returns flowOf(upgradeInfo)
        }
        // Layout flows must actually emit: WorkspaceRepo.state combines them, so a relaxed mock
        // would leave the state flow silent and every read of it would hang.
        val workspaceSettings = mockk<WorkspaceSettings>(relaxed = true).apply {
            every { layoutModePortrait.flow } returns flowOf(WorkspacePanelMode.AUTO)
            every { layoutModeLandscape.flow } returns flowOf(WorkspacePanelMode.AUTO)
        }
        return WorkspaceRepo(
            appScope = backgroundScope,
            factoryMap = Workspace.Type.entries.associateWith { FakeFactory() },
            workspaceSettings = workspaceSettings,
            operationsManager = operationsManager,
            upgradeRepo = upgradeRepo,
        )
    }

    private suspend fun WorkspaceRepo.infoFor(id: Workspace.Id): Workspace.Info =
        state.first().infos.single { it.id == id }

    private suspend fun WorkspaceRepo.workspaceIds(): List<Workspace.Id> =
        state.first().infos.map { it.id }

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
    fun `saver with a caller is exempt while a null-caller saver still counts`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        // Fill 4 of 5 slots with normal tabs.
        repeat(WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT - 1) { repo.createTab() }
        val tab = createdWorkspaces.first().id

        // A modal APK-export Saver (caller set) does not consume a slot.
        repo.execute(
            WorkspaceAction.Create(
                type = Workspace.Type.SAVER,
                arguments = FakePickerArguments(Workspace.Type.SAVER, tab),
            )
        ).shouldBeInstanceOf<WorkspaceAction.Create.Result.Success>()

        // The 5th normal tab still fits (the Saver child did not count).
        repo.execute(createReq(Workspace.Type.EXPLORER))
            .shouldBeInstanceOf<WorkspaceAction.Create.Result.Success>()

        // Now at the limit: an ACTION_SEND Saver (no caller) is a normal tab and is blocked.
        repo.execute(
            WorkspaceAction.Create(
                type = Workspace.Type.SAVER,
                arguments = FakeArguments(Workspace.Type.SAVER),
            )
        ).shouldBeInstanceOf<WorkspaceAction.Create.Result.LimitReached>()
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

    // ==================== Content-path dedup ====================

    private val pathA = LocalPath.build("/test/a.txt")
    private val pathB = LocalPath.build("/test/b.txt")

    private fun contentReq(
        path: APath<*>?,
        type: Workspace.Type = Workspace.Type.EDITOR,
        id: Workspace.Id? = null,
        skipLimitCheck: Boolean = false,
        replace: Workspace.Id? = null,
    ): WorkspaceAction.Create = WorkspaceAction.Create(
        type = type,
        arguments = FakeContentArguments(type, path),
        id = id,
        skipLimitCheck = skipLimitCheck,
        replace = replace,
    )

    private suspend fun WorkspaceRepo.createContentTab(path: APath<*>?): Workspace.Id =
        (execute(contentReq(path)) as WorkspaceAction.Create.Result.Success).newId

    @Test
    fun `same content path returns AlreadyOpen instead of duplicating`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val firstId = repo.createContentTab(pathA)

        val second = repo.execute(contentReq(pathA))

        second.shouldBeInstanceOf<WorkspaceAction.Create.Result.AlreadyOpen>()
        second.existingId shouldBe firstId
    }

    @Test
    fun `different content paths create separate workspaces`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        repo.createContentTab(pathA)

        repo.execute(contentReq(pathB)).shouldBeInstanceOf<WorkspaceAction.Create.Result.Success>()
        createdWorkspaces shouldHaveSize 2
    }

    @Test
    fun `null content path never dedups`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        repo.createContentTab(null)

        repo.execute(contentReq(null)).shouldBeInstanceOf<WorkspaceAction.Create.Result.Success>()
        createdWorkspaces shouldHaveSize 2
    }

    @Test
    fun `same content path with a different type creates separately`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        repo.createContentTab(pathA)

        repo.execute(contentReq(pathA, type = Workspace.Type.EXPLORER))
            .shouldBeInstanceOf<WorkspaceAction.Create.Result.Success>()
    }

    @Test
    fun `session restore bypasses content dedup`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        repo.createContentTab(pathA)

        // Saved sessions are the source of truth; a restored duplicate must come back as a tab
        repo.execute(contentReq(pathA, skipLimitCheck = true))
            .shouldBeInstanceOf<WorkspaceAction.Create.Result.Success>()
        createdWorkspaces shouldHaveSize 2
    }

    @Test
    fun `replace targeting the content holder itself proceeds`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val holderId = repo.createContentTab(pathA)

        repo.execute(contentReq(pathA, replace = holderId))
            .shouldBeInstanceOf<WorkspaceAction.Create.Result.Success>()
    }

    @Test
    fun `content dedup wins over the free-tier limit`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        val holderId = repo.createContentTab(pathA)
        repeat(WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT - 1) { repo.createTab() }

        // At the limit, re-opening an open file must focus it, never show the upgrade dialog
        val result = repo.execute(contentReq(pathA))
        result.shouldBeInstanceOf<WorkspaceAction.Create.Result.AlreadyOpen>()
        result.existingId shouldBe holderId
        repo.pendingConfirmations.first() shouldBe emptyMap()
    }

    // ==================== Content-path claims ====================

    private val claimantId = Workspace.Id()

    @Test
    fun `claim on an open path returns AlreadyOpen`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val holderId = repo.createContentTab(pathA)

        val result = repo.execute(WorkspaceAction.ClaimContentPath(Workspace.Type.EDITOR, pathA, claimantId))

        result.shouldBeInstanceOf<WorkspaceAction.ClaimContentPath.Result.AlreadyOpen>()
        result.existingId shouldBe holderId
    }

    @Test
    fun `granted claim blocks Create and second claim until released`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        repo.execute(WorkspaceAction.ClaimContentPath(Workspace.Type.EDITOR, pathA, claimantId))
            .shouldBeInstanceOf<WorkspaceAction.ClaimContentPath.Result.Granted>()

        val blockedCreate = repo.execute(contentReq(pathA))
        blockedCreate.shouldBeInstanceOf<WorkspaceAction.Create.Result.AlreadyOpen>()
        blockedCreate.existingId shouldBe claimantId

        val blockedClaim = repo.execute(WorkspaceAction.ClaimContentPath(Workspace.Type.EDITOR, pathA, Workspace.Id()))
        blockedClaim.shouldBeInstanceOf<WorkspaceAction.ClaimContentPath.Result.AlreadyOpen>()

        repo.execute(WorkspaceAction.ReleaseContentPath(claimantId, pathA))
        repo.execute(contentReq(pathA)).shouldBeInstanceOf<WorkspaceAction.Create.Result.Success>()
    }

    @Test
    fun `re-claiming an own claim stays granted`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        repo.execute(WorkspaceAction.ClaimContentPath(Workspace.Type.EDITOR, pathA, claimantId))
        repo.execute(WorkspaceAction.ClaimContentPath(Workspace.Type.EDITOR, pathA, claimantId))
            .shouldBeInstanceOf<WorkspaceAction.ClaimContentPath.Result.Granted>()
    }

    @Test
    fun `release by non-owner is a no-op`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        repo.execute(WorkspaceAction.ClaimContentPath(Workspace.Type.EDITOR, pathA, claimantId))

        repo.execute(WorkspaceAction.ReleaseContentPath(Workspace.Id(), pathA))

        repo.execute(contentReq(pathA)).shouldBeInstanceOf<WorkspaceAction.Create.Result.AlreadyOpen>()
    }

    @Test
    fun `replacing the claimant releases its claims`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val tabId = repo.createTab(type = Workspace.Type.EDITOR)
        repo.execute(WorkspaceAction.ClaimContentPath(Workspace.Type.EDITOR, pathA, tabId))

        repo.execute(
            WorkspaceAction.Create(
                type = Workspace.Type.EXPLORER,
                arguments = FakeArguments(Workspace.Type.EXPLORER),
                replace = tabId,
            )
        ).shouldBeInstanceOf<WorkspaceAction.Create.Result.Success>()

        repo.execute(contentReq(pathA)).shouldBeInstanceOf<WorkspaceAction.Create.Result.Success>()
    }

    @Test
    fun `closing the claimant releases its claims`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val tabId = repo.createTab(type = Workspace.Type.EDITOR)
        repo.execute(WorkspaceAction.ClaimContentPath(Workspace.Type.EDITOR, pathA, tabId))

        repo.execute(WorkspaceAction.Close(tabId))

        repo.execute(contentReq(pathA)).shouldBeInstanceOf<WorkspaceAction.Create.Result.Success>()
    }

    // ==================== Content-path dedup in batches ====================

    @Test
    fun `batch pre-resolves an already-open content path without consuming a slot`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo(isPro = false)
            val holderId = repo.createContentTab(pathA)
            // One slot stays free: it must go to pathB because the pathA re-open resolves
            // to AlreadyOpen without consuming it
            repeat(WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT - 2) { repo.createTab() }

            val result = repo.createBatch(contentReq(pathA), contentReq(pathB))

            result.skippedCount shouldBe 0
            val values = result.results.values.toList()
            values.filterIsInstance<WorkspaceAction.CreateBatch.CreationResult.AlreadyOpen>()
                .single().existingId shouldBe holderId
            values.count { it is WorkspaceAction.CreateBatch.CreationResult.Success } shouldBe 1
        }

    @Test
    fun `distinct same-path requests in one batch create once and defer duplicates to AlreadyOpen`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo(isPro = false)

            val result = repo.createBatch(
                contentReq(pathA, id = Workspace.Id()),
                contentReq(pathA, id = Workspace.Id()),
            )

            createdWorkspaces shouldHaveSize 1
            val values = result.results.values.toList()
            values.count { it is WorkspaceAction.CreateBatch.CreationResult.Success } shouldBe 1
            val alreadyOpen = values
                .filterIsInstance<WorkspaceAction.CreateBatch.CreationResult.AlreadyOpen>()
                .single()
            alreadyOpen.existingId shouldBe createdWorkspaces.single().id
        }

    @Test
    fun `identical same-path requests in one batch collapse to a single Success`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo(isPro = false)
            val request = contentReq(pathA)

            val result = repo.createBatch(request, request)

            createdWorkspaces shouldHaveSize 1
            result.results.size shouldBe 1
            result.results.values.single()
                .shouldBeInstanceOf<WorkspaceAction.CreateBatch.CreationResult.Success>()
        }

    @Test
    fun `deferred dupe of a limit-skipped primary counts as skipped, not failed`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo(isPro = false)
            repeat(WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT - 1) { repo.createTab() }

            // One slot: B takes it, A is limit-filtered, so A's deferred dup has no instance to
            // resolve to - it must count as skipped instead of surfacing a bogus Failure
            val result = repo.createBatch(
                contentReq(pathB, id = Workspace.Id()),
                contentReq(pathA, id = Workspace.Id()),
                contentReq(pathA, id = Workspace.Id()),
            )

            result.skippedCount shouldBe 2
            val values = result.results.values.toList()
            values.count { it is WorkspaceAction.CreateBatch.CreationResult.Success } shouldBe 1
            values.count { it is WorkspaceAction.CreateBatch.CreationResult.Failure } shouldBe 0
        }

    @Test
    fun `deferred same-path duplicates do not consume quota slots`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        repeat(WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT - 2) { repo.createTab() }

        // Two slots left: dupA must not burn one, so A + dupA + B all resolve without skips
        val result = repo.createBatch(
            contentReq(pathA, id = Workspace.Id()),
            contentReq(pathA, id = Workspace.Id()),
            contentReq(pathB, id = Workspace.Id()),
        )

        result.skippedCount shouldBe 0
        val values = result.results.values.toList()
        values.count { it is WorkspaceAction.CreateBatch.CreationResult.Success } shouldBe 2
        values.count { it is WorkspaceAction.CreateBatch.CreationResult.AlreadyOpen } shouldBe 1
    }

    // ==================== Paused workspaces ====================

    private suspend fun WorkspaceRepo.registerPaused(
        type: Workspace.Type = Workspace.Type.EXPLORER,
        id: Workspace.Id = Workspace.Id(),
        arguments: Workspace.Arguments = FakeArguments(type),
    ): Workspace.Id {
        val result = execute(WorkspaceAction.RegisterPaused(id = id, type = type, arguments = arguments))
        return (result as WorkspaceAction.RegisterPaused.Result.Success).newId
    }

    @Test
    fun `registering a paused workspace never invokes a factory`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()

        val pausedId = repo.registerPaused()

        // Registration derives an identity from the factory, but must never build the workspace
        createdWorkspaces shouldHaveSize 0
        repo.infoFor(pausedId).lifecycleState shouldBe Workspace.LifecycleState.Paused()
        // Typed consumers must see a paused id exactly like an id that doesn't exist yet
        repo.retrieve(pausedId).first() shouldBe null
    }

    @Test
    fun `a paused workspace shows the factory-derived identity`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        derivedDisplay = WorkspaceDisplay(
            title = "/sdcard/Download".toCaString(),
            subtitle = "Storage".toCaString(),
        )

        val pausedId = repo.registerPaused()

        val info = repo.infoFor(pausedId)
        info.title.get(context) shouldBe "/sdcard/Download"
        info.subtitle!!.get(context) shouldBe "Storage"
    }

    @Test
    fun `a type without a derivation falls back to the type label`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        derivedDisplay = null

        val pausedId = repo.registerPaused(type = Workspace.Type.APPS)

        repo.infoFor(pausedId).title.get(context) shouldBe Workspace.Type.APPS.label.get(context)
        repo.infoFor(pausedId).subtitle shouldBe null
    }

    @Test
    fun `a broken derivation still registers the workspace`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        nextDeriveFailure = IllegalStateException("Derivation exploded")

        val pausedId = repo.registerPaused(type = Workspace.Type.EXPLORER)

        // A broken derivation must never fail session restore
        repo.infoFor(pausedId).title.get(context) shouldBe Workspace.Type.EXPLORER.label.get(context)
        repo.infoFor(pausedId).lifecycleState shouldBe Workspace.LifecycleState.Paused()
    }

    @Test
    fun `registering a paused workspace whose arguments are of another type is rejected`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()

            val result = repo.execute(
                WorkspaceAction.RegisterPaused(
                    id = Workspace.Id(),
                    type = Workspace.Type.EXPLORER,
                    arguments = FakeArguments(Workspace.Type.SEARCHER),
                )
            )

            result.shouldBeInstanceOf<WorkspaceAction.RegisterPaused.Result.Failed>()
            repo.workspaceIds() shouldBe emptyList()
        }

    @Test
    fun `registering a paused workspace emits a Created event without auto focus`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val events = mutableListOf<WorkspaceEvent>()
            repo.events.onEach { events += it }.launchIn(backgroundScope)

            val pausedId = repo.registerPaused()

            val created = events.filterIsInstance<WorkspaceEvent.Created>().single()
            created.workspaceId shouldBe pausedId
            created.replacedId shouldBe null
            created.autoFocus shouldBe false
        }

    @Test
    fun `resuming replaces the stand-in at the same position`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val firstId = repo.createTab()
        val pausedId = repo.registerPaused()
        val lastId = repo.createTab()

        repo.execute(WorkspaceAction.Resume(pausedId))
            .shouldBeInstanceOf<WorkspaceAction.Resume.Result.Success>()

        repo.workspaceIds() shouldBe listOf(firstId, pausedId, lastId)
        repo.retrieve(pausedId).first() shouldNotBe null
        createdWorkspaces.count { it.id == pausedId } shouldBe 1
    }

    @Test
    fun `resuming an unknown or already live workspace is a no-op`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val liveId = repo.createTab()

        repo.execute(WorkspaceAction.Resume(Workspace.Id())) shouldBe WorkspaceAction.Resume.Result.NoOp
        repo.execute(WorkspaceAction.Resume(liveId)) shouldBe WorkspaceAction.Resume.Result.NoOp
        createdWorkspaces shouldHaveSize 1
    }

    @Test
    fun `concurrent resume invokes the factory exactly once`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val pausedId = repo.registerPaused()

        val results = listOf(
            async { repo.execute(WorkspaceAction.Resume(pausedId)) },
            async { repo.execute(WorkspaceAction.Resume(pausedId)) },
        ).awaitAll()

        createdWorkspaces.count { it.id == pausedId } shouldBe 1
        results.count { it is WorkspaceAction.Resume.Result.Success } shouldBe 1
        results.count { it is WorkspaceAction.Resume.Result.NoOp } shouldBe 1
    }

    @Test
    fun `a failed resume keeps the stand-in and can be retried`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val pausedId = repo.registerPaused()
        val boom = IllegalStateException("Factory exploded")
        nextCreateFailure = boom

        val failed = repo.execute(WorkspaceAction.Resume(pausedId))

        failed.shouldBeInstanceOf<WorkspaceAction.Resume.Result.Failed>()
        failed.error shouldBe boom
        // Never LifecycleState.Error - that state would compose the typed page host for a stand-in
        repo.infoFor(pausedId).lifecycleState shouldBe Workspace.LifecycleState.Paused(boom)
        repo.retrieve(pausedId).first() shouldBe null

        repo.execute(WorkspaceAction.Resume(pausedId))
            .shouldBeInstanceOf<WorkspaceAction.Resume.Result.Success>()
        repo.retrieve(pausedId).first() shouldNotBe null
        repo.infoFor(pausedId).lifecycleState shouldBe Workspace.LifecycleState.Initializing
    }

    @Test
    fun `a create for a content path held by a paused workspace resolves to it`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val pausedId = repo.registerPaused(
                type = Workspace.Type.EDITOR,
                arguments = FakeContentArguments(Workspace.Type.EDITOR, pathA),
            )

            val result = repo.execute(contentReq(pathA))

            result.shouldBeInstanceOf<WorkspaceAction.Create.Result.AlreadyOpen>()
            result.existingId shouldBe pausedId
            createdWorkspaces shouldHaveSize 0
        }

    @Test
    fun `paused workspaces count toward the free tier limit`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        repeat(WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT) { repo.registerPaused() }

        repo.execute(createReq(Workspace.Type.EXPLORER))
            .shouldBeInstanceOf<WorkspaceAction.Create.Result.LimitReached>()
    }

    @Test
    fun `a paused singleton blocks creating a second instance`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val pausedId = repo.registerPaused(type = Workspace.Type.DEVELOPER)

        val second = repo.execute(
            WorkspaceAction.Create(
                type = Workspace.Type.DEVELOPER,
                arguments = FakeArguments(Workspace.Type.DEVELOPER),
            )
        )

        second.shouldBeInstanceOf<WorkspaceAction.Create.Result.AlreadyOpen>()
        second.existingId shouldBe pausedId
    }

    @Test
    fun `registering a paused workspace with a used id fails instead of duplicating`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val existingId = repo.createTab()

            val result = repo.execute(
                WorkspaceAction.RegisterPaused(
                    id = existingId,
                    type = Workspace.Type.EXPLORER,
                    arguments = FakeArguments(Workspace.Type.EXPLORER),
                )
            )

            result.shouldBeInstanceOf<WorkspaceAction.RegisterPaused.Result.Failed>()
            repo.workspaceIds() shouldBe listOf(existingId)
        }

    // ==================== Custom titles ====================

    private suspend fun WorkspaceRepo.rename(id: Workspace.Id, title: String?): Boolean =
        (execute(WorkspaceAction.Rename(id, title)) as WorkspaceAction.Rename.Result).success

    /**
     * [Workspace.Info.displayTitle] falls back to [Workspace.Info.title] by returning that very
     * instance, so the fallback is asserted by identity rather than by resolved content.
     */
    private suspend fun WorkspaceRepo.showsAutomaticTitle(id: Workspace.Id) {
        val info = infoFor(id)
        info.customTitle shouldBe null
        info.displayTitle shouldBeSameInstanceAs info.title
    }

    @Test
    fun `renaming sets customTitle and displayTitle without touching the automatic title`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val id = repo.createTab()
            val automaticTitle = repo.infoFor(id).title

            repo.rename(id, "Holiday photos") shouldBe true

            val info = repo.infoFor(id)
            info.customTitle shouldBe "Holiday photos"
            info.displayTitle.get(context) shouldBe "Holiday photos"
            // The automatic title is untouched, it is merely overlaid
            info.title shouldBeSameInstanceAs automaticTitle
            info.title.get(context) shouldBe "Fake ${Workspace.Type.EXPLORER}"
        }

    /**
     * Paused stand-ins now show a factory-derived identity rather than a bare type label, so this
     * is where the two naming mechanisms meet: a name the user set must still win, and the derived
     * one must stay intact underneath so clearing the custom name reveals it again.
     */
    @Test
    fun `a custom name wins over the derived identity of a paused workspace`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            derivedDisplay = WorkspaceDisplay(
                title = "/sdcard/Download".toCaString(),
                subtitle = "Storage".toCaString(),
            )
            val pausedId = repo.registerPaused()

            repo.rename(pausedId, "Holiday photos") shouldBe true

            val info = repo.infoFor(pausedId)
            info.displayTitle.get(context) shouldBe "Holiday photos"
            info.title.get(context) shouldBe "/sdcard/Download"
            // The derived subtitle is untouched by renaming
            info.subtitle!!.get(context) shouldBe "Storage"
            info.lifecycleState shouldBe Workspace.LifecycleState.Paused()

            repo.rename(pausedId, null) shouldBe true

            repo.showsAutomaticTitle(pausedId)
            repo.infoFor(pausedId).displayTitle.get(context) shouldBe "/sdcard/Download"
        }

    @Test
    fun `renaming emits a Renamed event with the normalized title`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val events = mutableListOf<WorkspaceEvent>()
        repo.events.onEach { events += it }.launchIn(backgroundScope)
        val id = repo.createTab()

        repo.rename(id, "  Named  ")

        events.filterIsInstance<WorkspaceEvent.Renamed>().single().let {
            it.workspaceId shouldBe id
            it.customTitle shouldBe "Named"
        }
    }

    @Test
    fun `blank names clear the custom title and restore the automatic one`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()

            listOf("", "   ", null).forEach { blank ->
                val id = repo.createTab()
                repo.rename(id, "Named") shouldBe true
                repo.infoFor(id).customTitle shouldBe "Named"

                repo.rename(id, blank) shouldBe true

                repo.showsAutomaticTitle(id)
                repo.execute(WorkspaceAction.Close(id))
            }
        }

    @Test
    fun `control characters and newlines are stripped from a custom title`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val id = repo.createTab()

            repo.rename(id, "Line\none\ttwo\u0000")

            repo.infoFor(id).customTitle shouldBe "Lineonetwo"
        }

    @Test
    fun `a control-character-only name clears the custom title`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val id = repo.createTab()
        repo.rename(id, "Named")

        repo.rename(id, "\n\t\u0000") shouldBe true

        repo.infoFor(id).customTitle shouldBe null
    }

    @Test
    fun `an over-long custom title is truncated`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val id = repo.createTab()

        repo.rename(id, "x".repeat(WorkspaceAction.Rename.MAX_CUSTOM_TITLE_LENGTH + 50))

        repo.infoFor(id).customTitle shouldBe "x".repeat(WorkspaceAction.Rename.MAX_CUSTOM_TITLE_LENGTH)
    }

    @Test
    fun `renaming an unknown id fails and mutates nothing`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val id = repo.createTab()
        repo.rename(id, "Named")

        repo.rename(Workspace.Id(), "Ghost") shouldBe false

        repo.infoFor(id).customTitle shouldBe "Named"
        repo.state.first().infos.count { it.customTitle != null } shouldBe 1
    }

    @Test
    fun `closing a workspace drops its custom title`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val id = repo.createTab()
        repo.rename(id, "Named")

        repo.execute(WorkspaceAction.Close(id))

        // A fresh random id could not detect a stale entry, so recreate with the SAME id
        repo.execute(
            WorkspaceAction.Create(
                type = Workspace.Type.EXPLORER,
                arguments = FakeArguments(Workspace.Type.EXPLORER),
                id = id,
            )
        ).shouldBeInstanceOf<WorkspaceAction.Create.Result.Success>()

        repo.showsAutomaticTitle(id)
    }

    @Test
    fun `CloseAll clears all custom titles`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val idA = repo.createTab()
        val idB = repo.createTab()
        repo.rename(idA, "A")
        repo.rename(idB, "B")

        repo.execute(WorkspaceAction.CloseAll)

        val revivedId = repo.execute(
            WorkspaceAction.Create(
                type = Workspace.Type.EXPLORER,
                arguments = FakeArguments(Workspace.Type.EXPLORER),
                id = idA,
            )
        ).let { (it as WorkspaceAction.Create.Result.Success).newId }

        repo.infoFor(revivedId).customTitle shouldBe null
    }

    @Test
    fun `a same-id replacement keeps the custom title`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val id = repo.createTab()
        repo.rename(id, "Named")

        repo.execute(
            WorkspaceAction.Create(
                type = Workspace.Type.SEARCHER,
                arguments = FakeArguments(Workspace.Type.SEARCHER),
                replace = id,
                id = id,
            )
        ).shouldBeInstanceOf<WorkspaceAction.Create.Result.Success>()

        repo.infoFor(id).customTitle shouldBe "Named"
    }

    @Test
    fun `a replacement with a new id carries the custom title over`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val originalId = repo.createTab()
        repo.rename(originalId, "Named")

        val result = repo.execute(
            WorkspaceAction.Create(
                type = Workspace.Type.SEARCHER,
                arguments = FakeArguments(Workspace.Type.SEARCHER),
                replace = originalId,
            )
        ) as WorkspaceAction.Create.Result.Success

        result.newId shouldNotBe originalId
        repo.infoFor(result.newId).customTitle shouldBe "Named"
        repo.state.first().infos.count { it.customTitle != null } shouldBe 1
    }

    @Test
    fun `a failed replacement leaves the custom title on the original`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val originalId = repo.createTab()
        repo.rename(originalId, "Named")
        nextCreateFailure = IllegalStateException("Factory exploded")

        runCatching {
            repo.execute(
                WorkspaceAction.Create(
                    type = Workspace.Type.SEARCHER,
                    arguments = FakeArguments(Workspace.Type.SEARCHER),
                    replace = originalId,
                )
            )
        }

        repo.infoFor(originalId).customTitle shouldBe "Named"
    }

    @Test
    fun `clearing a custom title reveals the latest automatic title`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val id = repo.createTab()
        repo.rename(id, "Named")

        // The automatic title keeps updating underneath while the custom one is shown
        val ws = fake(id)
        val latestAutomaticTitle = "/new/path".toCaString()
        ws.info.value = ws.info.value.copy(title = latestAutomaticTitle)
        repo.infoFor(id).customTitle shouldBe "Named"

        repo.rename(id, null)

        // Not merely "some automatic title" - the newest one
        repo.infoFor(id).displayTitle shouldBeSameInstanceAs latestAutomaticTitle
    }

    @Test
    fun `concurrently renamed workspaces never exchange titles`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val idA = repo.createTab()
        val idB = repo.createTab()

        listOf(
            async { repo.rename(idA, "A") },
            async { repo.rename(idB, "B") },
        ).awaitAll()

        repo.infoFor(idA).customTitle shouldBe "A"
        repo.infoFor(idB).customTitle shouldBe "B"
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
