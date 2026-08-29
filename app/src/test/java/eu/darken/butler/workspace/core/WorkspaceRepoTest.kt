package eu.darken.butler.workspace.core

import android.content.Context
import android.os.Parcel
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.contracts.viewer.ViewerArguments
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import eu.darken.butler.workspace.core.undo.ClosedWorkspaceStash
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.usage.WorkspaceUsageRepo
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

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

    /**
     * A sub-workspace that owes its caller no result and can opt into being paused with it — the
     * app-details case. [FakePickerArguments] must stay refused, so it cannot stand in for this.
     */
    private class FakeChildArguments(
        override val type: Workspace.Type,
        override val callerWorkspaceId: Workspace.Id?,
        override val pausableAsChild: Boolean = true,
    ) : Workspace.ArgumentsWithCaller {
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

        /** What [createArguments] returns; set to simulate state drifting away from creation args. */
        var currentArguments: Workspace.Arguments = arguments

        /** When set, [createArguments] throws it. */
        var argumentsError: Throwable? = null

        /** When set, [release] throws it after marking the workspace released. */
        var releaseError: Throwable? = null

        /** Runs inside [createArguments], for exercising state changes while it suspends. */
        var whileCapturingArguments: (suspend () -> Unit)? = null

        /** Runs inside [release], for exercising state changes while a close is in flight. */
        var whileReleasing: (suspend () -> Unit)? = null

        override val info = MutableStateFlow(
            Workspace.Info(
                id = id,
                type = type,
                title = "Fake $type".toCaString(),
                callerWorkspaceId = (arguments as? Workspace.ArgumentsWithCaller)?.callerWorkspaceId,
                modalPresentation = (arguments as? Workspace.ArgumentsWithCaller)?.modalPresentation
                    ?: Workspace.ModalPresentationMode.PANE_LOCAL,
                // Mirrors initialInfo(): lifecycle decisions read the projected flag, not the arguments
                pausableAsChild = arguments.isPausableAsChild,
                contentPath = (arguments as? Workspace.ArgumentsWithContentPath)?.contentPath,
            )
        )

        override suspend fun createArguments(): Workspace.Arguments {
            whileCapturingArguments?.invoke()
            argumentsError?.let { throw it }
            return currentArguments
        }

        override suspend fun release() {
            released = true
            whileReleasing?.invoke()
            releaseError?.let { throw it }
        }

        fun markReady() {
            info.value = info.value.copy(lifecycleState = Workspace.LifecycleState.Ready)
        }
    }

    private val createdWorkspaces = mutableListOf<FakeWorkspace>()

    /** When set, the next [FakeFactory.create] throws it instead of creating a workspace. */
    private var nextCreateFailure: Exception? = null

    /** Ids whose next [FakeFactory.create] throws, for failing one specific member of a unit. */
    private val createFailures = mutableMapOf<Workspace.Id, Throwable>()

    /** When set, the next [FakeFactory.deriveDisplay] throws it instead of deriving an identity. */
    private var nextDeriveFailure: Exception? = null

    /** Identity every [FakeFactory] derives; null models a type without a derivation. */
    private var derivedDisplay: WorkspaceDisplay? = null

    private inner class FakeFactory : WorkspaceFactory<Workspace.Arguments> {
        override fun create(id: Workspace.Id, arguments: Workspace.Arguments): Workspace<Workspace.Arguments> {
            createFailures.remove(id)?.let { throw it }
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

    private val usageRepo: WorkspaceUsageRepo = mockk(relaxed = true)

    // Resource ids resolve to a stable stand-in so CaStrings can be compared by resolved value
    // (CaString has no structural equality)
    private val context: Context = mockk<Context>().apply {
        every { getString(any()) } answers { "res-${firstArg<Int>()}" }
    }

    /** The stash the repo under test writes to; replaced per [createRepo] call. */
    private lateinit var closedStash: ClosedWorkspaceStash

    private fun TestScope.createRepo(isPro: Boolean = false): WorkspaceRepo {
        closedStash = ClosedWorkspaceStash(backgroundScope)
        val upgradeInfo = mockk<UpgradeRepo.Info>().apply {
            every { this@apply.isPro } returns isPro
            every { isSettled } returns true
            every { error } returns null
        }
        // Mirrors the real repo: a hot flow that never completes. The settled-aware upgrade gates
        // suspend on it, and a finite flow would end that wait and trip their fail-open path.
        val upgradeRepo = mockk<UpgradeRepo>().apply {
            every { this@apply.upgradeInfo } returns MutableStateFlow(upgradeInfo)
            coEvery { refresh() } just Runs
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
            usageRepo = usageRepo,
            closedStash = closedStash,
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

    /** Creates a sub-workspace under a chosen id, the only way to build ownership that cannot resolve. */
    private suspend fun WorkspaceRepo.createSubWorkspaceWithId(
        id: Workspace.Id,
        caller: Workspace.Id,
        type: Workspace.Type = Workspace.Type.EXPLORER,
    ): Workspace.Id {
        val result = execute(
            WorkspaceAction.Create(type = type, arguments = FakePickerArguments(type, caller), id = id)
        )
        return (result as WorkspaceAction.Create.Result.Success).newId
    }

    private fun createReq(
        type: Workspace.Type,
        id: Workspace.Id? = null,
        createdAt: Instant? = null,
    ): WorkspaceAction.Create = WorkspaceAction.Create(
        type = type,
        arguments = FakeArguments(type),
        id = id,
        createdAt = createdAt,
    )

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
    fun `closing either member of a caller cycle takes both down`() = runTest(UnconfinedTestDispatcher()) {
        val first = Workspace.Id()
        val second = Workspace.Id()

        listOf(first, second).forEach { closed ->
            val repo = createRepo()
            // Nothing validates caller ids at creation time, so a cycle is reachable - and the close
            // recursion only removes a member after its children went, so it has to guard itself
            repo.createSubWorkspaceWithId(id = first, caller = second)
            repo.createSubWorkspaceWithId(id = second, caller = first)

            repo.execute(WorkspaceAction.Close(closed))

            repo.state.first().infos shouldHaveSize 0
        }
    }

    @Test
    fun `closing a self-referencing workspace terminates`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val id = Workspace.Id()
        repo.createSubWorkspaceWithId(id = id, caller = id)

        repo.execute(WorkspaceAction.Close(id))

        repo.state.first().infos shouldHaveSize 0
    }

    @Test
    fun `a unit order expands to the full list, members staying with their owner`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val first = repo.createTab()
            val second = repo.createTab()
            val overlay = repo.createSubWorkspace(caller = first)
            val nested = repo.createSubWorkspace(caller = overlay)

            repo.execute(WorkspaceAction.Reorder(listOf(second, first)))
                .shouldBeInstanceOf<WorkspaceAction.Reorder.Result>().success shouldBe true

            repo.workspaceIds() shouldBe listOf(second, first, overlay, nested)
        }

    @Test
    fun `a reorder that omits a unit is refused and changes nothing`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val first = repo.createTab()
        val second = repo.createTab()
        repo.createSubWorkspace(caller = first)
        val before = repo.workspaceIds()

        repo.execute(WorkspaceAction.Reorder(listOf(second)))
            .shouldBeInstanceOf<WorkspaceAction.Reorder.Result>().success shouldBe false

        repo.workspaceIds() shouldBe before
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
    fun `a same-id replacement keeps its sub-workspaces`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val originalId = repo.createTab()
        val childId = repo.createSubWorkspace(caller = originalId)

        // The tab is rebuilt in place, so nothing was orphaned - a viewer rebinding to the file its
        // own Saver just wrote must not take that Saver down with it.
        repo.execute(
            WorkspaceAction.Create(
                type = Workspace.Type.SEARCHER,
                arguments = FakeArguments(Workspace.Type.SEARCHER),
                replace = originalId,
                id = originalId,
            )
        )

        fake(childId).released shouldBe false
        repo.retrieve(childId).first() shouldNotBe null
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

    // ==================== Limit recovery ====================

    /** A create that opts into the limit dialog's "close the oldest tab" action (createAndFocus). */
    private suspend fun WorkspaceRepo.createRecoverable(
        type: Workspace.Type = Workspace.Type.EXPLORER,
        arguments: Workspace.Arguments = FakeArguments(type),
    ): WorkspaceAction.Create.Result = execute(
        WorkspaceAction.Create(type = type, arguments = arguments, allowLimitRecovery = true)
    ) as WorkspaceAction.Create.Result

    private suspend fun WorkspaceRepo.createReadyTabAt(
        createdAt: Instant,
        type: Workspace.Type = Workspace.Type.EXPLORER,
    ): Workspace.Id {
        val result = execute(
            WorkspaceAction.Create(type = type, arguments = FakeArguments(type), createdAt = createdAt)
        )
        return (result as WorkspaceAction.Create.Result.Success).newId.also { fake(it).markReady() }
    }

    /** Fills the free tier with ready tabs; index 0 is the oldest. */
    private suspend fun WorkspaceRepo.fillWithReadyTabs(
        count: Int = WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT,
    ): List<Workspace.Id> = (1..count).map { createReadyTab() }

    /** A restored tab: [WorkspaceAction.Create.skipLimitCheck] lets the counted count exceed the tier. */
    private suspend fun WorkspaceRepo.createRestoredReadyTab(
        type: Workspace.Type = Workspace.Type.EXPLORER,
    ): Workspace.Id {
        val result = execute(
            WorkspaceAction.Create(type = type, arguments = FakeArguments(type), skipLimitCheck = true)
        )
        return (result as WorkspaceAction.Create.Result.Success).newId.also { fake(it).markReady() }
    }

    private suspend fun WorkspaceRepo.limitConfirmation(): PendingWorkspaceConfirmation =
        pendingConfirmations.first().values.single {
            it.data is PendingWorkspaceConfirmation.ConfirmationData.WorkspaceLimitReached
        }

    private suspend fun WorkspaceRepo.limitDialog(): PendingWorkspaceConfirmation.ConfirmationData.WorkspaceLimitReached =
        limitConfirmation().data as PendingWorkspaceConfirmation.ConfirmationData.WorkspaceLimitReached

    private suspend fun WorkspaceRepo.countedTabs(): Int =
        state.first().infos.count { !it.isSubWorkspace && !it.type.isQuotaExempt }

    /** The tabs the dialog offers to close, in the order it lists them. */
    private suspend fun WorkspaceRepo.closableIds(): List<Workspace.Id> =
        limitDialog().candidates.filter { it.isClosable }.map { it.id }

    private suspend fun WorkspaceRepo.blockerFor(id: Workspace.Id): WorkspaceLimitCandidate.Blocker? =
        limitDialog().candidates.single { it.id == id }.blocker

    /** Resolves the open limit dialog by closing exactly [victims], as the dialog's confirm action does. */
    private suspend fun WorkspaceRepo.resolveLimit(vararg victims: Workspace.Id) =
        resolveLimitByClosing(limitConfirmation().id, victims.toSet())

    @Test
    fun `the offered tabs are listed oldest first, not in list order`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        val newest = repo.createReadyTabAt(Instant.fromEpochSeconds(500))
        val oldest = repo.createReadyTabAt(Instant.fromEpochSeconds(100))
        val middle = repo.createReadyTabAt(Instant.fromEpochSeconds(300))
        val rest = listOf(
            repo.createReadyTabAt(Instant.fromEpochSeconds(700)),
            repo.createReadyTabAt(Instant.fromEpochSeconds(900)),
        )
        // List order deliberately disagrees with age, in both directions
        repo.execute(WorkspaceAction.Reorder(listOf(middle, oldest, newest) + rest))
            .shouldBeInstanceOf<WorkspaceAction.Reorder.Result>()

        repo.createRecoverable().shouldBeInstanceOf<WorkspaceAction.Create.Result.LimitReached>()

        repo.closableIds() shouldBe listOf(oldest, middle, newest) + rest
    }

    @Test
    fun `a tab with unsaved changes is listed but never closable`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        val ids = repo.fillWithReadyTabs()
        markDirty(ids[0])

        repo.createRecoverable()

        repo.blockerFor(ids[0]) shouldBe WorkspaceLimitCandidate.Blocker.UNSAVED_CHANGES
        repo.closableIds() shouldBe ids.drop(1)
    }

    @Test
    fun `a busy tab is listed but never closable`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        val ids = repo.fillWithReadyTabs()
        fake(ids[0]).info.update { it.copy(operationCount = 1) }

        repo.createRecoverable()

        repo.blockerFor(ids[0]) shouldBe WorkspaceLimitCandidate.Blocker.BUSY
        repo.closableIds() shouldBe ids.drop(1)
    }

    @Test
    fun `a tab needing attention is listed but never closable`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        val ids = repo.fillWithReadyTabs()
        fake(ids[0]).info.update { it.copy(attentionCount = 1) }

        repo.createRecoverable()

        repo.blockerFor(ids[0]) shouldBe WorkspaceLimitCandidate.Blocker.NEEDS_ATTENTION
        repo.closableIds() shouldBe ids.drop(1)
    }

    @Test
    fun `a tab holding a content claim is listed but never closable`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        val ids = repo.fillWithReadyTabs()
        repo.execute(WorkspaceAction.ClaimContentPath(Workspace.Type.EXPLORER, pathA, ids[0]))

        repo.createRecoverable()

        repo.blockerFor(ids[0]) shouldBe WorkspaceLimitCandidate.Blocker.BUSY
        repo.closableIds() shouldBe ids.drop(1)
    }

    @Test
    fun `a tab that is still initializing is listed but never closable`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        // Zero counters while setup is still running say nothing about what closing would cost
        val initializing = repo.createTab()
        val ids = listOf(initializing) + repo.fillWithReadyTabs(WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT - 1)

        repo.createRecoverable()

        repo.blockerFor(ids[0]) shouldBe WorkspaceLimitCandidate.Blocker.LOADING
        repo.closableIds() shouldBe ids.drop(1)
    }

    /**
     * A drill-down is not a dialog: an Apps tab with an app's details open is a tab with a detail
     * view, and closing the tab is exactly what the user picked from the list.
     */
    @Test
    fun `a tab with an informational drill-down stays closable`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        val ids = repo.fillWithReadyTabs()
        repo.createReadyChild(caller = ids[0])

        repo.createRecoverable()

        repo.blockerFor(ids[0]) shouldBe null
        repo.closableIds() shouldBe ids
    }

    /** The row names what the user is looking at, which is the top of the stack, not the tab's root. */
    @Test
    fun `a stacked tab is named by the top of its stack`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        val ids = repo.fillWithReadyTabs()
        repo.createReadyChild(caller = ids[0], type = Workspace.Type.APP_DETAILS)

        repo.createRecoverable()

        val stacked = repo.limitDialog().candidates.single { it.id == ids[0] }
        stacked.type shouldBe Workspace.Type.APP_DETAILS
        stacked.stackDepth shouldBe 1
        stacked.title.get(context) shouldBe "Fake APP_DETAILS"
        // A plain tab still names itself and carries no badge
        val plain = repo.limitDialog().candidates.single { it.id == ids[1] }
        plain.type shouldBe Workspace.Type.EXPLORER
        plain.stackDepth shouldBe 0
        plain.title.get(context) shouldBe "Fake EXPLORER"
    }

    /** A name the user typed belongs to the tab, so it outranks whatever is stacked on top of it. */
    @Test
    fun `a renamed tab keeps its custom title over the top of the stack`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo(isPro = false)
            val ids = repo.fillWithReadyTabs()
            repo.createReadyChild(caller = ids[0], type = Workspace.Type.APP_DETAILS)
            repo.rename(ids[0], "My apps")

            repo.createRecoverable()

            val stacked = repo.limitDialog().candidates.single { it.id == ids[0] }
            stacked.title.get(context) shouldBe "My apps"
            // Still identifies as the stacked content in every other respect
            stacked.type shouldBe Workspace.Type.APP_DETAILS
            stacked.stackDepth shouldBe 1
        }

    /**
     * A picker owes its caller a result that no longer has anywhere to go once the tab is closed.
     * The picker here is deliberately left un-ready: owing a result must outrank the transient
     * "still loading", or a freshly opened picker would explain itself with the wrong reason.
     */
    @Test
    fun `a tab with an open picker is listed but never closable`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        val ids = repo.fillWithReadyTabs()
        repo.createSubWorkspace(caller = ids[0])

        repo.createRecoverable()

        repo.blockerFor(ids[0]) shouldBe WorkspaceLimitCandidate.Blocker.AWAITING_RESULT
        repo.closableIds() shouldBe ids.drop(1)
    }

    /**
     * The regression that lifting the blanket stacked-tab refusal could have introduced: Close()
     * takes the whole stack down, so a root that looks idle is not safe when its child is not.
     */
    @Test
    fun `a tab whose stacked child is dirty is blocked by the child`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        val ids = repo.fillWithReadyTabs()
        val child = repo.createReadyChild(caller = ids[0])
        markDirty(child)

        repo.createRecoverable()

        repo.blockerFor(ids[0]) shouldBe WorkspaceLimitCandidate.Blocker.UNSAVED_CHANGES
        repo.closableIds() shouldBe ids.drop(1)
    }

    @Test
    fun `a tab whose stacked child is busy is blocked by the child`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        val ids = repo.fillWithReadyTabs()
        val child = repo.createReadyChild(caller = ids[0])
        fake(child).info.update { it.copy(operationCount = 1) }

        repo.createRecoverable()

        repo.blockerFor(ids[0]) shouldBe WorkspaceLimitCandidate.Blocker.BUSY
        repo.closableIds() shouldBe ids.drop(1)
    }

    @Test
    fun `a tab whose stacked child needs attention is blocked by the child`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo(isPro = false)
            val ids = repo.fillWithReadyTabs()
            val child = repo.createReadyChild(caller = ids[0])
            fake(child).info.update { it.copy(attentionCount = 1) }

            repo.createRecoverable()

            repo.blockerFor(ids[0]) shouldBe WorkspaceLimitCandidate.Blocker.NEEDS_ATTENTION
            repo.closableIds() shouldBe ids.drop(1)
        }

    @Test
    fun `a tab whose stacked child is still initializing is blocked by the child`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo(isPro = false)
            val ids = repo.fillWithReadyTabs()
            // Created but never marked ready
            repo.execute(
                WorkspaceAction.Create(
                    type = Workspace.Type.APP_DETAILS,
                    arguments = FakeChildArguments(Workspace.Type.APP_DETAILS, ids[0], true),
                )
            )

            repo.createRecoverable()

            repo.blockerFor(ids[0]) shouldBe WorkspaceLimitCandidate.Blocker.LOADING
            repo.closableIds() shouldBe ids.drop(1)
        }

    @Test
    fun `a tab whose stacked child holds a content claim is blocked by the child`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo(isPro = false)
            val ids = repo.fillWithReadyTabs()
            val child = repo.createReadyChild(caller = ids[0])
            repo.execute(WorkspaceAction.ClaimContentPath(Workspace.Type.EXPLORER, pathA, child))

            repo.createRecoverable()

            repo.blockerFor(ids[0]) shouldBe WorkspaceLimitCandidate.Blocker.BUSY
            repo.closableIds() shouldBe ids.drop(1)
        }

    /**
     * Owing a result is structural, so it has to outrank a transient state anywhere else in the
     * unit - otherwise an initializing root would explain an open picker as "still loading".
     */
    @Test
    fun `an open picker outranks an initializing root`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        // A root that never became ready, with a picker stacked on it
        val loadingRoot = repo.createTab()
        val rest = repo.fillWithReadyTabs(WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT - 1)
        repo.createSubWorkspace(caller = loadingRoot)

        repo.createRecoverable()

        repo.blockerFor(loadingRoot) shouldBe WorkspaceLimitCandidate.Blocker.AWAITING_RESULT
        repo.closableIds() shouldBe rest
    }

    /**
     * The intra-unit version of the mid-close race: the tab is torn down member by member, and every
     * release suspends, so a sibling child can turn dirty while the first one is closing. Closing it
     * anyway would destroy work inside a tab the user only agreed to close while it was safe.
     */
    @Test
    fun `a stacked child that turns dirty mid-close leaves its tab open`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo(isPro = false)
            val ids = repo.fillWithReadyTabs()
            val childA = repo.createReadyChild(caller = ids[0])
            val childB = repo.createReadyChild(caller = ids[0])
            repo.createRecoverable(type = Workspace.Type.SEARCHER)
            // Releasing one child dirties its sibling, which the up-front check could not have seen
            fake(childB).whileReleasing = { markDirty(childA) }

            repo.resolveLimit(ids[0])

            // The tab and the child that turned dirty both survive
            fake(childA).released shouldBe false
            repo.retrieve(ids[0]).first() shouldNotBe null
            // The replacement is built before anything is closed, so it exists but must be abandoned
            val abandoned = createdWorkspaces.single { it.type == Workspace.Type.SEARCHER }
            abandoned.released shouldBe true
            repo.retrieve(abandoned.id).first() shouldBe null
            repo.countedTabs() shouldBe WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT
        }

    /** Closing a stacked tab frees exactly one slot: only its root ever counted against the quota. */
    @Test
    fun `closing a stacked tab takes the whole stack and frees one slot`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        val ids = repo.fillWithReadyTabs()
        val child = repo.createReadyChild(caller = ids[0])
        repo.createRecoverable(type = Workspace.Type.SEARCHER)

        repo.resolveLimit(ids[0])

        repo.retrieve(ids[0]).first() shouldBe null
        repo.retrieve(child).first() shouldBe null
        createdWorkspaces.count { it.type == Workspace.Type.SEARCHER } shouldBe 1
        repo.countedTabs() shouldBe WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT
    }

    /**
     * The sub-workspace is never its own row: it is part of the tab it sits on, which is the thing
     * that gets closed. It lends that row its name, not a second entry.
     */
    @Test
    fun `a sub-workspace is not listed as its own row`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        val ids = repo.fillWithReadyTabs()
        val sub = repo.createReadyChild(caller = ids[0])

        repo.createRecoverable()

        repo.limitDialog().candidates.map { it.id } shouldBe ids
        repo.limitDialog().candidates.none { it.id == sub } shouldBe true
    }

    @Test
    fun `no closable tab still lists them, offers nothing and parks nothing`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        val ids = repo.fillWithReadyTabs()
        ids.forEach { markDirty(it) }

        repo.createRecoverable()

        val dialog = repo.limitDialog()
        // Listed anyway: seeing what holds the slots beats being told a bare number
        dialog.candidates.map { it.id } shouldBe ids
        dialog.candidates.all { it.blocker == WorkspaceLimitCandidate.Blocker.UNSAVED_CHANGES } shouldBe true
        dialog.canRecover shouldBe false

        // Nothing was parked, so the confirm action cannot fire behind the UI's back
        repo.resolveLimit(ids[0])
        createdWorkspaces shouldHaveSize WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT
        ids.forEach { repo.retrieve(it).first() shouldNotBe null }
    }

    /**
     * A restore overshoot does not withhold the offer any more - it raises the price. One tab would
     * not be enough, so the dialog asks for as many as it takes.
     */
    @Test
    fun `above the limit the offer asks for more than one tab`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo(isPro = false)
            // Session restore bypasses the limit, so the counted count can legitimately exceed it
            repeat(WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT + 1) {
                val result = repo.execute(
                    WorkspaceAction.Create(
                        type = Workspace.Type.EXPLORER,
                        arguments = FakeArguments(Workspace.Type.EXPLORER),
                        skipLimitCheck = true,
                    )
                )
                fake((result as WorkspaceAction.Create.Result.Success).newId).markReady()
            }

            repo.createRecoverable().shouldBeInstanceOf<WorkspaceAction.Create.Result.LimitReached>()

            val dialog = repo.limitDialog()
            dialog.canRecover shouldBe true
            dialog.currentCount shouldBe WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT + 1
            dialog.minToClose shouldBe 2
        }

    /** Too few tabs closable for it to help: listed for information, but nothing on offer. */
    @Test
    fun `too little closable withholds the offer entirely`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        // 7 counted tabs of which only 2 may go: 5 would remain, which is still the whole quota
        val stuck = repo.fillWithReadyTabs()
        stuck.forEach { markDirty(it) }
        val free = listOf(repo.createRestoredReadyTab(), repo.createRestoredReadyTab())

        repo.createRecoverable().shouldBeInstanceOf<WorkspaceAction.Create.Result.LimitReached>()

        val dialog = repo.limitDialog()
        dialog.candidates.map { it.id } shouldBe stuck + free
        dialog.candidates.filter { it.isClosable }.map { it.id } shouldBe free
        dialog.canRecover shouldBe false

        // Nothing parked, so confirming closes nothing
        repo.resolveLimit(free[0], free[1])
        repo.workspaceIds() shouldBe stuck + free
    }

    @Test
    fun `a create that did not opt in gets no recovery offer`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        repo.fillWithReadyTabs()

        repo.execute(createReq(Workspace.Type.EXPLORER))
            .shouldBeInstanceOf<WorkspaceAction.Create.Result.LimitReached>()

        val dialog = repo.limitDialog()
        dialog.candidates.shouldBeEmpty()
        dialog.canRecover shouldBe false
    }

    @Test
    fun `a batch-triggered limit dialog lists no tabs`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        repo.fillWithReadyTabs()

        repo.createBatch(createReq(Workspace.Type.EXPLORER))

        val dialog = repo.limitDialog()
        dialog.candidates.shouldBeEmpty()
        dialog.canRecover shouldBe false
    }

    @Test
    fun `resolving closes the picked tab, completes the create and selects it`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo(isPro = false)
            val events = mutableListOf<WorkspaceEvent>()
            repo.events.onEach { events += it }.launchIn(backgroundScope)
            val ids = repo.fillWithReadyTabs()
            repo.createRecoverable(type = Workspace.Type.SEARCHER)
                .shouldBeInstanceOf<WorkspaceAction.Create.Result.LimitReached>()

            repo.resolveLimit(ids[0])

            fake(ids[0]).released shouldBe true
            repo.retrieve(ids[0]).first() shouldBe null
            repo.countedTabs() shouldBe WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT
            val newId = createdWorkspaces.single { it.type == Workspace.Type.SEARCHER }.id
            repo.retrieve(newId).first() shouldNotBe null
            events.filterIsInstance<WorkspaceEvent.Created>().last().workspaceId shouldBe newId
            events.filterIsInstance<WorkspaceEvent.SelectionRequested>().single().workspaceId shouldBe newId
            repo.pendingConfirmations.first() shouldBe emptyMap()
        }

    /** The whole point of the redesign: one dialog, several tabs gone. */
    @Test
    fun `resolving closes every tab the user picked`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        val ids = repo.fillWithReadyTabs()
        repo.createRecoverable(type = Workspace.Type.SEARCHER)
            .shouldBeInstanceOf<WorkspaceAction.Create.Result.LimitReached>()

        repo.resolveLimit(ids[0], ids[2], ids[4])

        listOf(ids[0], ids[2], ids[4]).forEach {
            fake(it).released shouldBe true
            repo.retrieve(it).first() shouldBe null
        }
        listOf(ids[1], ids[3]).forEach { repo.retrieve(it).first() shouldNotBe null }
        // Three closed, one created
        repo.countedTabs() shouldBe WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT - 2
        createdWorkspaces.count { it.type == Workspace.Type.SEARCHER } shouldBe 1
    }

    /**
     * [WorkspaceRepo.resolveLimitByClosing] is public, so the victim set is whatever a caller passed.
     * Closing an uncounted workspace would free no slot while the sufficiency check believed it had,
     * committing the create with the user still at the cap.
     */
    @Test
    fun `a victim that is not a counted tab is refused, not closed`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        repo.fillWithReadyTabs()
        // Quota-exempt but otherwise perfectly closable: a ready root tab with no children, so only
        // the counted-tab guard stands between it and being closed for a slot it does not occupy.
        val exempt = repo.createReadyTab(type = Workspace.Type.DEVELOPER)
        repo.createRecoverable(type = Workspace.Type.SEARCHER)

        repo.resolveLimit(exempt)

        fake(exempt).released shouldBe false
        repo.retrieve(exempt).first() shouldNotBe null
        createdWorkspaces.none { it.type == Workspace.Type.SEARCHER } shouldBe true
        repo.countedTabs() shouldBe WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT
    }

    @Test
    fun `closing all tabs drops the limit dialog and its parked create`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        val ids = repo.fillWithReadyTabs()
        repo.createRecoverable(type = Workspace.Type.SEARCHER)
            .shouldBeInstanceOf<WorkspaceAction.Create.Result.LimitReached>()
        val confirmationId = repo.limitConfirmation().id

        repo.execute(WorkspaceAction.CloseAll)

        repo.pendingConfirmations.first() shouldBe emptyMap()
        // The parked create must not survive to re-open a tab into the session the user just emptied
        repo.resolveLimitByClosing(confirmationId, ids.toSet())
        createdWorkspaces.none { it.type == Workspace.Type.SEARCHER } shouldBe true
    }

    /**
     * A tab's dirtiness is owned by the workspace, not by the repo lock, so it can change while an
     * earlier victim is still closing. Validating the set once up front and then closing blindly
     * would discard that tab's unsaved work.
     */
    @Test
    fun `a tab that turns dirty mid-close is left alone`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        val ids = repo.fillWithReadyTabs()
        repo.createRecoverable(type = Workspace.Type.SEARCHER)
        // ids[1] picks up unsaved changes while ids[0] is being released
        fake(ids[0]).whileReleasing = { markDirty(ids[1]) }

        repo.resolveLimit(ids[0], ids[1])

        fake(ids[0]).released shouldBe true
        fake(ids[1]).released shouldBe false
        repo.retrieve(ids[1]).first() shouldNotBe null
        // One slot was still freed, so the blocked create goes through
        createdWorkspaces.count { it.type == Workspace.Type.SEARCHER } shouldBe 1
    }

    /**
     * Same race, but now the survivor was the only slot that mattered: nothing may be committed on
     * top of a cap that is still full, so the user is asked again instead.
     */
    @Test
    fun `losing the only needed victim mid-close commits nothing and re-asks`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo(isPro = false)
            val ids = repo.fillWithReadyTabs()
            repo.createRecoverable(type = Workspace.Type.SEARCHER)
            // A restore pushes the count up, so both picked tabs are needed to get back under the cap
            val restored = repo.createRestoredReadyTab()
            fake(ids[0]).whileReleasing = { markDirty(ids[1]) }

            repo.resolveLimit(ids[0], ids[1])

            fake(ids[1]).released shouldBe false
            // The replacement is built before anything is closed, so this path builds one and then
            // has to abandon it: it must never reach the repo, and it must not leak either.
            repo.workspaceIds() shouldBe ids.drop(1) + restored
            createdWorkspaces.single { it.type == Workspace.Type.SEARCHER }.released shouldBe true
            // Asked again for what is still closable, rather than silently doing nothing
            repo.limitDialog().canRecover shouldBe true
        }

    /**
     * Closing fewer tabs than picked is safe - it is a subset of what the user consented to - so one
     * tab turning dirty does not throw away the rest of their choice.
     */
    @Test
    fun `a picked tab that stopped being closable is skipped, the rest still go`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo(isPro = false)
            val ids = repo.fillWithReadyTabs()
            repo.createRecoverable(type = Workspace.Type.SEARCHER)

            markDirty(ids[0])
            repo.resolveLimit(ids[0], ids[1])

            fake(ids[0]).released shouldBe false
            fake(ids[1]).released shouldBe true
            createdWorkspaces.count { it.type == Workspace.Type.SEARCHER } shouldBe 1
        }

    /**
     * The retry runs on the repo's own non-reentrant mutex. Going back through the public [execute]
     * would deadlock it permanently, so the repo has to stay usable afterwards.
     */
    @Test
    fun `resolving does not deadlock the repo`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        val ids = repo.fillWithReadyTabs()
        repo.createRecoverable(type = Workspace.Type.SEARCHER)

        repo.resolveLimit(ids[0])

        withTimeout(10.seconds) {
            repo.execute(WorkspaceAction.Reorder(repo.workspaceIds()))
                .shouldBeInstanceOf<WorkspaceAction.Reorder.Result>().success shouldBe true
        }
    }

    /**
     * The whole selection can evaporate, not just shrink. Substituting a tab the user never picked
     * is what a fresh dialog exists to prevent.
     */
    @Test
    fun `a selection that went entirely unclosable is not substituted but re-offered`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo(isPro = false)
            val ids = repo.fillWithReadyTabs()
            repo.createRecoverable(type = Workspace.Type.SEARCHER)
            repo.closableIds() shouldBe ids

            // The picked tab goes dirty while the dialog is up
            markDirty(ids[0])
            repo.resolveLimit(ids[0])

            fake(ids[0]).released shouldBe false
            createdWorkspaces.none { it.type == Workspace.Type.SEARCHER } shouldBe true
            // A fresh dialog lists what IS closable now, so the user consents to those
            repo.closableIds() shouldBe ids.drop(1)
        }

    @Test
    fun `a slot freed while the dialog is up creates without closing anything`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo(isPro = false)
            val ids = repo.fillWithReadyTabs()
            repo.createRecoverable(type = Workspace.Type.SEARCHER)

            repo.execute(WorkspaceAction.Close(ids.last()))
            repo.resolveLimit(ids[0])

            fake(ids[0]).released shouldBe false
            repo.retrieve(ids[0]).first() shouldNotBe null
            repo.countedTabs() shouldBe WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT
        }

    /**
     * Dedup is re-checked BEFORE the quota, exactly like the normal create path: re-opening something
     * that is open by now must never cost the user a tab.
     */
    @Test
    fun `a create that became AlreadyOpen selects the holder and closes nothing`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo(isPro = false)
            val events = mutableListOf<WorkspaceEvent>()
            repo.events.onEach { events += it }.launchIn(backgroundScope)
            val ids = repo.fillWithReadyTabs()
            repo.createRecoverable(
                type = Workspace.Type.EDITOR,
                arguments = FakeContentArguments(Workspace.Type.EDITOR, pathA),
            ).shouldBeInstanceOf<WorkspaceAction.Create.Result.LimitReached>()

            // The user opens that file another way meanwhile, staying at the limit
            repo.execute(WorkspaceAction.Close(ids.last()))
            val holderId = repo.createContentTab(pathA)

            repo.resolveLimit(ids[0])

            fake(ids[0]).released shouldBe false
            createdWorkspaces.count { it.type == Workspace.Type.EDITOR } shouldBe 1
            events.filterIsInstance<WorkspaceEvent.SelectionRequested>().single().workspaceId shouldBe holderId
        }

    @Test
    fun `a failing factory during recovery loses no tab`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        val ids = repo.fillWithReadyTabs()
        repo.createRecoverable(type = Workspace.Type.SEARCHER)
        nextCreateFailure = IllegalStateException("Factory exploded")

        repo.resolveLimit(ids[0])

        // Nothing is built, so nothing is destroyed
        fake(ids[0]).released shouldBe false
        repo.workspaceIds() shouldBe ids
        repo.countedTabs() shouldBe WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT
    }

    /**
     * Sufficiency is re-checked at resolve time, not just when the offer is made: restores keep
     * creating past the quota, so by now the picked tabs may free nothing.
     */
    @Test
    fun `a count that outgrew the limit while the dialog was up closes nothing`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo(isPro = false)
            val ids = repo.fillWithReadyTabs()
            repo.createRecoverable(type = Workspace.Type.SEARCHER)
                .shouldBeInstanceOf<WorkspaceAction.Create.Result.LimitReached>()
            repo.limitDialog().minToClose shouldBe 1

            val restored = listOf(repo.createRestoredReadyTab(), repo.createRestoredReadyTab())
            repo.resolveLimit(ids[0])

            fake(ids[0]).released shouldBe false
            repo.workspaceIds() shouldBe ids + restored
            repo.countedTabs() shouldBe WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT + 2
            createdWorkspaces.none { it.type == Workspace.Type.SEARCHER } shouldBe true
            // The fresh dialog asks for the count it now takes - one tab is no longer enough
            val reposted = repo.limitDialog()
            reposted.canRecover shouldBe true
            reposted.minToClose shouldBe 3
            reposted.candidates.map { it.id } shouldBe ids + restored

            // And closing that many actually completes the create the user was blocked on
            repo.resolveLimit(ids[0], ids[1], ids[2])
            createdWorkspaces.count { it.type == Workspace.Type.SEARCHER } shouldBe 1
            repo.countedTabs() shouldBe WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT
        }

    @Test
    fun `a failing recovery hands the failure to the caller`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        val ids = repo.fillWithReadyTabs()
        repo.createRecoverable(type = Workspace.Type.SEARCHER)
        val boom = IllegalStateException("Factory exploded")
        nextCreateFailure = boom

        val failure = repo.resolveLimit(ids[0]).await()

        failure shouldBeSameInstanceAs boom
    }

    @Test
    fun `a failing release during recovery still completes the create`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        val ids = repo.fillWithReadyTabs()
        repo.createRecoverable(type = Workspace.Type.SEARCHER)
        fake(ids[0]).releaseError = IllegalStateException("Engine stuck")

        repo.resolveLimit(ids[0])

        repo.retrieve(ids[0]).first() shouldBe null
        createdWorkspaces.count { it.type == Workspace.Type.SEARCHER } shouldBe 1
        repo.countedTabs() shouldBe WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT
    }

    // ==================== Creation timestamps ====================

    @Test
    fun `a create is stamped with the supplied instant, or with now`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val restored = Instant.fromEpochSeconds(1234)

        val stampedId = repo.createReadyTabAt(restored)
        val freshId = repo.createTab()

        repo.peekCreatedAt(stampedId) shouldBe restored
        repo.peekCreatedAt(freshId) shouldNotBe null
    }

    @Test
    fun `batch creates are stamped too, with the supplied instant when there is one`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val restored = Instant.fromEpochSeconds(2468)

            repo.createBatch(
                createReq(Workspace.Type.EXPLORER, createdAt = restored),
                createReq(Workspace.Type.SEARCHER),
            )

            createdWorkspaces.forEach { repo.peekCreatedAt(it.id) shouldNotBe null }
            val stamped = createdWorkspaces.single { it.type == Workspace.Type.EXPLORER }
            repo.peekCreatedAt(stamped.id) shouldBe restored
        }

    @Test
    fun `a paused registration keeps the persisted creation time`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val id = Workspace.Id()
        val restored = Instant.fromEpochSeconds(4321)

        repo.execute(
            WorkspaceAction.RegisterPaused(
                id = id,
                type = Workspace.Type.EXPLORER,
                arguments = FakeArguments(Workspace.Type.EXPLORER),
                createdAt = restored,
            )
        ).shouldBeInstanceOf<WorkspaceAction.RegisterPaused.Result.Success>()

        repo.peekCreatedAt(id) shouldBe restored
    }

    @Test
    fun `a pause and resume round-trip keeps the creation time`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val restored = Instant.fromEpochSeconds(777)
        val id = repo.createReadyTabAt(restored)

        repo.pause(id).shouldBeInstanceOf<WorkspaceAction.Pause.Result.Success>()
        repo.execute(WorkspaceAction.Resume(id)).shouldBeInstanceOf<WorkspaceAction.Resume.Result.Success>()

        repo.peekCreatedAt(id) shouldBe restored
    }

    @Test
    fun `closing a workspace drops its creation time`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val id = repo.createTab()

        repo.execute(WorkspaceAction.Close(id))

        repo.peekCreatedAt(id) shouldBe null
    }

    @Test
    fun `a replacement is stamped fresh and drops the replaced tab's time`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val originalId = repo.createReadyTabAt(Instant.fromEpochSeconds(100))

            val result = repo.execute(
                WorkspaceAction.Create(
                    type = Workspace.Type.SEARCHER,
                    arguments = FakeArguments(Workspace.Type.SEARCHER),
                    replace = originalId,
                )
            ).shouldBeInstanceOf<WorkspaceAction.Create.Result.Success>()

            repo.peekCreatedAt(originalId) shouldBe null
            repo.peekCreatedAt(result.newId) shouldNotBe Instant.fromEpochSeconds(100)
            repo.peekCreatedAt(result.newId) shouldNotBe null
        }

    @Test
    fun `CloseAll drops every creation time`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val ids = listOf(repo.createTab(), repo.createTab())

        repo.execute(WorkspaceAction.CloseAll)

        ids.forEach { repo.peekCreatedAt(it) shouldBe null }
    }

    private fun markDirty(id: Workspace.Id) {
        val ws = fake(id)
        ws.info.value = ws.info.value.copy(hasUnsavedChanges = true)
    }

    private fun markClean(id: Workspace.Id) {
        val ws = fake(id)
        ws.info.value = ws.info.value.copy(hasUnsavedChanges = false)
    }

    private fun Map<String, PendingWorkspaceConfirmation>.closeConfirmationsFor(id: Workspace.Id): Int =
        values.count {
            val data = it.data
            data is PendingWorkspaceConfirmation.ConfirmationData.WorkspaceCloseConfirmation &&
                data.workspaceId == id
        }

    private suspend fun WorkspaceRepo.closeConfirmationData(
        id: Workspace.Id,
    ): PendingWorkspaceConfirmation.ConfirmationData.WorkspaceCloseConfirmation = pendingConfirmations.first()
        .values
        .map { it.data }
        .filterIsInstance<PendingWorkspaceConfirmation.ConfirmationData.WorkspaceCloseConfirmation>()
        .single { it.workspaceId == id }

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
    fun `a dirty child blocks the close of its clean owner`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val tabId = repo.createTab()
            val childId = repo.createSubWorkspace(caller = tabId)
            markDirty(childId)

            repo.execute(WorkspaceAction.Close(tabId))

            // Closing the tab would take the child down with it, so nothing may be released yet
            fake(tabId).released shouldBe false
            fake(childId).released shouldBe false
            repo.pendingConfirmations.first().closeConfirmationsFor(tabId) shouldBe 1
        }

    @Test
    fun `the close confirmation names the member holding the unsaved changes`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val tabId = repo.createTab()
            val childId = repo.createSubWorkspace(caller = tabId)
            markDirty(childId)

            repo.execute(WorkspaceAction.Close(tabId))

            val data = repo.pendingConfirmations.first().values
                .map { it.data }
                .filterIsInstance<PendingWorkspaceConfirmation.ConfirmationData.WorkspaceCloseConfirmation>()
                .single()
            data.workspaceId shouldBe tabId
            data.hasUnsavedChanges shouldBe true
            data.workspaceTitle shouldBe repo.infoFor(childId).displayTitle
        }

    @Test
    fun `a dirty orphan is not reaped by a close of its missing caller`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val tabId = repo.createTab()
            val childId = repo.createSubWorkspace(caller = tabId)
            markDirty(childId)
            // executeClose enumerates children before it looks the target up, so a close naming an
            // id nothing holds still reaps whatever points at it
            val missingId = Workspace.Id()
            fake(childId).info.update { it.copy(callerWorkspaceId = missingId) }

            repo.execute(WorkspaceAction.Close(missingId))

            fake(childId).released shouldBe false
            repo.pendingConfirmations.first().closeConfirmationsFor(missingId) shouldBe 1
        }

    @Test
    fun `every unsaved member of the closing subtree is counted`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val tabId = repo.createTab()
            val firstChild = repo.createSubWorkspace(caller = tabId)
            val secondChild = repo.createSubWorkspace(caller = tabId)
            markDirty(tabId)
            markDirty(firstChild)
            markDirty(secondChild)

            repo.execute(WorkspaceAction.Close(tabId))

            val data = repo.pendingConfirmations.first().values
                .map { it.data }
                .filterIsInstance<PendingWorkspaceConfirmation.ConfirmationData.WorkspaceCloseConfirmation>()
                .single()
            data.hasUnsavedChanges shouldBe true
            data.unsavedCount shouldBe 3
        }

    @Test
    fun `a broader close supersedes a pending confirmation for one of its members`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val tabId = repo.createTab()
            val childId = repo.createSubWorkspace(caller = tabId)
            markDirty(childId)

            repo.execute(WorkspaceAction.Close(childId))
            repo.execute(WorkspaceAction.Close(tabId))

            // One dialog, one answer: the tab's close already decides the child's fate
            val pending = repo.pendingConfirmations.first()
            pending.closeConfirmationsFor(childId) shouldBe 0
            pending.closeConfirmationsFor(tabId) shouldBe 1
        }

    @Test
    fun `a narrower close supersedes a pending confirmation for its owner`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val tabId = repo.createTab()
            val childId = repo.createSubWorkspace(caller = tabId)
            markDirty(childId)

            repo.execute(WorkspaceAction.Close(tabId))
            repo.execute(WorkspaceAction.Close(childId))

            // The newest request wins rather than being dropped by the one it overlaps
            val pending = repo.pendingConfirmations.first()
            pending.closeConfirmationsFor(tabId) shouldBe 0
            pending.closeConfirmationsFor(childId) shouldBe 1
        }

    @Test
    fun `closes of unrelated units keep their own confirmations`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val firstId = repo.createTab()
            val secondId = repo.createTab()
            markDirty(firstId)
            markDirty(secondId)

            repo.execute(WorkspaceAction.Close(firstId))
            repo.execute(WorkspaceAction.Close(secondId))

            val pending = repo.pendingConfirmations.first()
            pending.closeConfirmationsFor(firstId) shouldBe 1
            pending.closeConfirmationsFor(secondId) shouldBe 1
        }

    @Test
    fun `a superseded confirmation cannot still close its target`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val tabId = repo.createTab()
            val childId = repo.createSubWorkspace(caller = tabId)
            markDirty(childId)

            repo.execute(WorkspaceAction.Close(tabId))
            val supersededId = repo.pendingConfirmations.first().keys.single()
            repo.execute(WorkspaceAction.Close(childId))

            // Resolving the id the superseded dialog carried must not reach a dropped action
            repo.resolveConfirmation(supersededId, confirmed = true)

            fake(tabId).released shouldBe false
            fake(childId).released shouldBe false
        }

    @Test
    fun `a dirty member inside a caller cycle still blocks the close`() =
        runTest(UnconfinedTestDispatcher()) {
            val first = Workspace.Id()
            val second = Workspace.Id()
            val repo = createRepo()
            // The guard walks the same unvalidated caller relation the close recursion does, so it
            // has to terminate on a cycle rather than spin looking for unsaved members
            repo.createSubWorkspaceWithId(id = first, caller = second)
            repo.createSubWorkspaceWithId(id = second, caller = first)
            markDirty(second)

            repo.execute(WorkspaceAction.Close(first))

            fake(first).released shouldBe false
            fake(second).released shouldBe false
            repo.pendingConfirmations.first().closeConfirmationsFor(first) shouldBe 1
        }

    @Test
    fun `a clean subtree closes without confirmation`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val tabId = repo.createTab()
            val childId = repo.createSubWorkspace(caller = tabId)

            repo.execute(WorkspaceAction.Close(tabId))

            fake(tabId).released shouldBe true
            fake(childId).released shouldBe true
            repo.pendingConfirmations.first() shouldBe emptyMap()
        }

    @Test
    fun `closing a clean child does not consult its dirty owner`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val tabId = repo.createTab()
            val childId = repo.createSubWorkspace(caller = tabId)
            markDirty(tabId)

            // A close names a subtree, not a whole unit: the owner outlives this and keeps its edits
            repo.execute(WorkspaceAction.Close(childId))

            fake(childId).released shouldBe true
            fake(tabId).released shouldBe false
            repo.pendingConfirmations.first() shouldBe emptyMap()
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

    /**
     * The tab manager's selection is confirmed once, for the whole set. Routing it through Close
     * would re-ask per dirty tab, leaving those tabs open behind dialogs the user has no reason to
     * expect after already choosing "Discard selected".
     */
    @Test
    fun `CloseSelected closes a dirty workspace without asking again`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val clean = repo.createTab()
            val dirty = repo.createTab()
            markDirty(dirty)

            val result = repo.execute(WorkspaceAction.CloseSelected(setOf(clean, dirty)))

            result shouldBe WorkspaceAction.CloseSelected.Result(closed = 2)
            fake(clean).released shouldBe true
            fake(dirty).released shouldBe true
            repo.retrieve(dirty).first() shouldBe null
            repo.pendingConfirmations.first() shouldBe emptyMap()
        }

    @Test
    fun `CloseSelected leaves unselected tabs open`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val closing = repo.createTab()
        val keeping = repo.createTab()

        repo.execute(WorkspaceAction.CloseSelected(setOf(closing)))

        repo.retrieve(closing).first() shouldBe null
        repo.retrieve(keeping).first() shouldNotBe null
        fake(keeping).released shouldBe false
    }

    /** A selection can name a tab that closed from another surface while the manager was open. */
    @Test
    fun `CloseSelected skips ids whose tab is already gone`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val live = repo.createTab()
        val gone = repo.createTab()
        repo.execute(WorkspaceAction.Close(gone))

        val result = repo.execute(WorkspaceAction.CloseSelected(setOf(live, gone)))

        result shouldBe WorkspaceAction.CloseSelected.Result(closed = 1)
        repo.retrieve(live).first() shouldBe null
    }

    @Test
    fun `a confirmation hosted by another tab dies with the tab it asks about`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val dirty = repo.createTab()
            val host = repo.createTab()
            markDirty(dirty)
            repo.execute(WorkspaceAction.Close(dirty, sourceWorkspaceId = host))
            repo.pendingConfirmations.first().closeConfirmationsFor(dirty) shouldBe 1

            // Closed by another route (session cleanup, its owner going away, a discarded edit)
            markClean(dirty)
            repo.execute(WorkspaceAction.Close(dirty))

            // Surviving it would leave a blocking dialog naming a dead tab, and confirming it would
            // close a workspace that no longer exists.
            repo.pendingConfirmations.first() shouldBe emptyMap()
        }

    @Test
    fun `a confirmation outlives the tab it borrowed as an anchor`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val dirty = repo.createTab()
            val host = repo.createTab()
            markDirty(dirty)
            repo.execute(WorkspaceAction.Close(dirty, sourceWorkspaceId = host))
            repo.pendingConfirmations.first().closeConfirmationsFor(dirty) shouldBe 1

            repo.execute(WorkspaceAction.Close(host))

            // The anchor is an unrelated tab, so it never rendered the question - losing it takes
            // nothing away, while dropping the confirmation would silently abandon the close.
            repo.pendingConfirmations.first().closeConfirmationsFor(dirty) shouldBe 1
            repo.retrieve(dirty).first() shouldNotBe null
        }

    @Test
    fun `a confirmation dies with the child layer that asked it`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val tabId = repo.createTab()
            val childId = repo.createSubWorkspace(caller = tabId)
            markDirty(tabId)
            repo.execute(WorkspaceAction.Close(tabId, sourceWorkspaceId = childId))
            repo.pendingConfirmations.first().closeConfirmationsFor(tabId) shouldBe 1

            repo.execute(WorkspaceAction.Close(childId))

            // The layer rendering it is gone, so there is nothing left to answer in.
            repo.pendingConfirmations.first() shouldBe emptyMap()
            repo.retrieve(tabId).first() shouldNotBe null
        }

    @Test
    fun `a close names its own anchor as part of what it removes`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val id = repo.createTab()
            markDirty(id)

            repo.execute(WorkspaceAction.Close(id))

            repo.closeConfirmationData(id).hostInClosingSubtree shouldBe true
        }

    @Test
    fun `a close invoked from a child anchors inside what it removes`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val tabId = repo.createTab()
            val childId = repo.createSubWorkspace(caller = tabId)
            markDirty(tabId)

            repo.execute(WorkspaceAction.Close(tabId, sourceWorkspaceId = childId))

            // The child goes down with the tab, so the layer it asked from can host the question.
            repo.closeConfirmationData(tabId).hostInClosingSubtree shouldBe true
        }

    @Test
    fun `a close invoked from an unrelated tab anchors outside what it removes`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val dirty = repo.createTab()
            val host = repo.createTab()
            markDirty(dirty)

            repo.execute(WorkspaceAction.Close(dirty, sourceWorkspaceId = host))

            repo.closeConfirmationData(dirty).hostInClosingSubtree shouldBe false
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
        skipContentDedup: Boolean = false,
    ): WorkspaceAction.Create = WorkspaceAction.Create(
        type = type,
        arguments = FakeContentArguments(type, path),
        id = id,
        skipLimitCheck = skipLimitCheck,
        replace = replace,
        skipContentDedup = skipContentDedup,
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
    fun `a create that opted out commits onto a path another tab holds`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val ownId = repo.createContentTab(pathB)
        repo.createContentTab(pathA)

        // A workspace binding itself to a path it just produced: sending the user to the foreign
        // tab instead would abandon the one they were working in.
        repo.execute(contentReq(pathA, replace = ownId, id = ownId, skipContentDedup = true))
            .shouldBeInstanceOf<WorkspaceAction.Create.Result.Success>()
        repo.infoFor(ownId).contentPath shouldBe pathA
    }

    @Test
    fun `a replace of something else still dedups`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val holderId = repo.createContentTab(pathA)
        val otherId = repo.createContentTab(pathB)

        // Replaces are not exempt as a class; only the opt-out above skips the check.
        val result = repo.execute(contentReq(pathA, replace = otherId))
        result.shouldBeInstanceOf<WorkspaceAction.Create.Result.AlreadyOpen>()
        result.existingId shouldBe holderId
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

    // ==================== Viewer drill-downs ====================

    private suspend fun WorkspaceRepo.createViewer(
        path: APath<*>,
        caller: Workspace.Id? = null,
    ): Workspace.Id {
        val result = execute(
            WorkspaceAction.Create(
                type = Workspace.Type.VIEWER,
                arguments = ViewerArguments.Default(filePath = path, callerWorkspaceId = caller),
            )
        )
        return (result as WorkspaceAction.Create.Result.Success).newId
    }

    /**
     * A drill-down publishes its content path like any viewer, but dedup skips sub-workspace creates
     * and only matches non-sub holders, so it can neither trigger nor satisfy a match.
     */
    @Test
    fun `a viewer drill-down opens alongside a tab on the same path`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val ownerId = repo.createTab()
        val tabId = repo.createViewer(pathA)

        val drillDownId = repo.createViewer(pathA, caller = ownerId)

        drillDownId shouldNotBe tabId
        repo.infoFor(drillDownId).isSubWorkspace shouldBe true
        repo.infoFor(drillDownId).contentPath shouldBe pathA
    }

    @Test
    fun `an open drill-down does not block a tab for the same path`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val ownerId = repo.createTab()
        repo.createViewer(pathA, caller = ownerId)

        repo.execute(
            WorkspaceAction.Create(
                type = Workspace.Type.VIEWER,
                arguments = ViewerArguments.Default(filePath = pathA),
            )
        ).shouldBeInstanceOf<WorkspaceAction.Create.Result.Success>()
    }

    @Test
    fun `a second same-path viewer tab still returns AlreadyOpen`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val ownerId = repo.createTab()
        val tabId = repo.createViewer(pathA)
        repo.createViewer(pathA, caller = ownerId)

        val second = repo.execute(
            WorkspaceAction.Create(
                type = Workspace.Type.VIEWER,
                arguments = ViewerArguments.Default(filePath = pathA),
            )
        )

        second.shouldBeInstanceOf<WorkspaceAction.Create.Result.AlreadyOpen>()
        second.existingId shouldBe tabId
    }

    /** Restore builds the tab past dedup, but what it produces still holds the path for later. */
    @Test
    fun `a restored viewer tab keeps deduping later opens of its path`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val restoredId = repo.execute(
            WorkspaceAction.Create(
                type = Workspace.Type.VIEWER,
                arguments = ViewerArguments.Default(filePath = pathA),
                skipLimitCheck = true,
            )
        ).let { (it as WorkspaceAction.Create.Result.Success).newId }

        val reopened = repo.execute(
            WorkspaceAction.Create(
                type = Workspace.Type.VIEWER,
                arguments = ViewerArguments.Default(filePath = pathA),
            )
        )

        reopened.shouldBeInstanceOf<WorkspaceAction.Create.Result.AlreadyOpen>()
        reopened.existingId shouldBe restoredId
    }

    /** Pause captures the arguments and resume rebuilds from them, caller included. */
    @Test
    fun `a paused and resumed drill-down stays under the same root`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val ownerId = repo.createReadyTab()
        val drillDownId = repo.createViewer(pathA, caller = ownerId).also { fake(it).markReady() }

        repo.pause(drillDownId).shouldBeInstanceOf<WorkspaceAction.Pause.Result.Success>()
        repo.execute(WorkspaceAction.Resume(drillDownId))
            .shouldBeInstanceOf<WorkspaceAction.Resume.Result.Success>()

        val stacks = WorkspaceStacks(repo.state.first().infos)
        stacks.rootOf(drillDownId)?.id shouldBe ownerId
        repo.infoFor(drillDownId).isSubWorkspace shouldBe true
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

    @Test
    fun `batch creation carries the source workspace on every Created event`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val sourceId = repo.createTab()
            val events = mutableListOf<WorkspaceEvent>()
            repo.events.onEach { events += it }.launchIn(backgroundScope)

            repo.execute(
                WorkspaceAction.CreateBatch(
                    requests = listOf(
                        contentReq(pathA, id = Workspace.Id()),
                        contentReq(pathB, id = Workspace.Id()),
                    ),
                    sourceWorkspaceId = sourceId,
                )
            ).shouldBeInstanceOf<WorkspaceAction.CreateBatch.Result.Success>()

            val created = events.filterIsInstance<WorkspaceEvent.Created>()
            created shouldHaveSize 2
            created.forEach { it.sourceWorkspaceId shouldBe sourceId }
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
            // Session restore has no origin pane, so there is no placement hint to carry
            created.sourceWorkspaceId shouldBe null
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

    /**
     * Pause refuses sub-workspaces, so registration does too - a paused modal is not a state the
     * repo should hand out, however the arguments reached it.
     */
    @Test
    fun `registering a paused sub-workspace fails instead of resurrecting a modal`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val callerId = repo.createTab()

            val result = repo.execute(
                WorkspaceAction.RegisterPaused(
                    id = Workspace.Id(),
                    type = Workspace.Type.EXPLORER,
                    arguments = FakePickerArguments(Workspace.Type.EXPLORER, callerId),
                )
            )

            result.shouldBeInstanceOf<WorkspaceAction.RegisterPaused.Result.Failed>()
            repo.workspaceIds() shouldBe listOf(callerId)
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

    // ==================== Pausing ====================

    private suspend fun WorkspaceRepo.createReadyTab(
        type: Workspace.Type = Workspace.Type.EXPLORER,
    ): Workspace.Id = createTab(type).also { fake(it).markReady() }

    /** A ready modal child that owes no result, i.e. one that may go down with its owner. */
    private suspend fun WorkspaceRepo.createReadyChild(
        caller: Workspace.Id,
        type: Workspace.Type = Workspace.Type.APP_DETAILS,
        pausableAsChild: Boolean = true,
    ): Workspace.Id {
        val result = execute(
            WorkspaceAction.Create(
                type = type,
                arguments = FakeChildArguments(type, caller, pausableAsChild),
            )
        )
        return (result as WorkspaceAction.Create.Result.Success).newId.also { fake(it).markReady() }
    }

    private suspend fun WorkspaceRepo.pause(id: Workspace.Id): WorkspaceAction.Result =
        execute(WorkspaceAction.Pause(id))

    @Test
    fun `pausing swaps in a stand-in at the same position and keeps the identity`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val firstId = repo.createReadyTab()
            val pausedId = repo.createReadyTab()
            val lastId = repo.createReadyTab()
            val title = "Downloads".toCaString()
            val subtitle = "/storage/emulated/0/Download".toCaString()
            fake(pausedId).info.update { it.copy(title = title, subtitle = subtitle, contentPath = pathA) }

            repo.pause(pausedId).shouldBeInstanceOf<WorkspaceAction.Pause.Result.Success>().id shouldBe pausedId

            repo.workspaceIds() shouldBe listOf(firstId, pausedId, lastId)
            val info = repo.infoFor(pausedId)
            info.isPaused shouldBe true
            info.title shouldBe title
            info.subtitle shouldBe subtitle
            info.contentPath shouldBe pathA
            // Typed consumers must see it exactly like a workspace that was never instantiated
            repo.retrieve(pausedId).first() shouldBe null
            fake(pausedId).released shouldBe true
        }

    @Test
    fun `pausing captures the current arguments, not the creation arguments`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val id = repo.createReadyTab(Workspace.Type.EDITOR)
            fake(id).currentArguments = FakeContentArguments(Workspace.Type.EDITOR, pathB)

            repo.pause(id)

            // The stand-in holds the state as it was at pause time, not what the tab opened with
            repo.peek(id)!!.createArguments() shouldBe FakeContentArguments(Workspace.Type.EDITOR, pathB)
        }

    @Test
    fun `resuming a paused workspace rebuilds it from the captured arguments`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val id = repo.createReadyTab(Workspace.Type.EDITOR)
            fake(id).currentArguments = FakeContentArguments(Workspace.Type.EDITOR, pathB)
            repo.pause(id)

            repo.execute(WorkspaceAction.Resume(id))
                .shouldBeInstanceOf<WorkspaceAction.Resume.Result.Success>()

            repo.retrieve(id).first() shouldNotBe null
            createdWorkspaces.count { it.id == id } shouldBe 2
            repo.infoFor(id).contentPath shouldBe pathB
        }

    @Test
    fun `a failing release still leaves the workspace paused`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val id = repo.createReadyTab()
        fake(id).releaseError = IllegalStateException("Engine stuck")

        repo.pause(id).shouldBeInstanceOf<WorkspaceAction.Pause.Result.Success>()

        repo.infoFor(id).isPaused shouldBe true
    }

    @Test
    fun `a failing createArguments keeps the workspace live and untouched`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val id = repo.createReadyTab()
            val boom = IllegalStateException("Cannot serialize state")
            fake(id).argumentsError = boom

            val result = repo.pause(id)

            result.shouldBeInstanceOf<WorkspaceAction.Pause.Result.Failed>()
            result.error shouldBe boom
            repo.infoFor(id).isPaused shouldBe false
            repo.retrieve(id).first() shouldNotBe null
            fake(id).released shouldBe false
        }

    @Test
    fun `a cancellation while capturing arguments propagates instead of becoming a failure`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val id = repo.createReadyTab()
            fake(id).argumentsError = CancellationException("Scope died")

            shouldThrow<CancellationException> { repo.pause(id) }

            repo.infoFor(id).isPaused shouldBe false
            fake(id).released shouldBe false
        }

    @Test
    fun `pausing an unknown or already paused workspace is a no-op`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val id = repo.createReadyTab()
        repo.pause(id)

        repo.pause(Workspace.Id()) shouldBe WorkspaceAction.Pause.Result.NoOp
        repo.pause(id) shouldBe WorkspaceAction.Pause.Result.NoOp
    }

    @Test
    fun `pausing a picker sub-workspace refuses its whole unit`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val parentId = repo.createReadyTab()
        val childId = repo.createSubWorkspace(caller = parentId)
        fake(childId).markReady()

        // Both doors into the unit refuse: a picker owes its caller a result, and the collector for
        // it lives in the caller that would be released too
        repo.pause(childId)
            .shouldBeInstanceOf<WorkspaceAction.Pause.Result.Refused>()
            .reason shouldBe WorkspaceAction.Pause.Reason.HAS_CHILDREN
        repo.pause(parentId)
            .shouldBeInstanceOf<WorkspaceAction.Pause.Result.Refused>()
            .reason shouldBe WorkspaceAction.Pause.Reason.HAS_CHILDREN
        repo.infoFor(parentId).isPaused shouldBe false
        repo.infoFor(childId).isPaused shouldBe false
    }

    @Test
    fun `a picker deeper in the stack refuses the whole unit`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val rootId = repo.createReadyTab(Workspace.Type.APPS)
        val childId = repo.createReadyChild(caller = rootId)
        val pickerId = repo.createSubWorkspace(caller = childId)
        fake(pickerId).markReady()

        val result = repo.pause(rootId)

        result.shouldBeInstanceOf<WorkspaceAction.Pause.Result.Refused>()
        result.reason shouldBe WorkspaceAction.Pause.Reason.HAS_CHILDREN
        fake(rootId).released shouldBe false
        fake(childId).released shouldBe false
    }

    @Test
    fun `a child that opted out refuses the whole unit`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val rootId = repo.createReadyTab(Workspace.Type.APPS)
        repo.createReadyChild(caller = rootId, pausableAsChild = false)

        val result = repo.pause(rootId)

        result.shouldBeInstanceOf<WorkspaceAction.Pause.Result.Refused>()
        result.reason shouldBe WorkspaceAction.Pause.Reason.HAS_CHILDREN
    }

    @Test
    fun `a child that cannot be released right now refuses the whole unit`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val rootId = repo.createReadyTab(Workspace.Type.APPS)
            // The Saver case: it opts in, but a transient export flow lives only in the instance
            val childId = repo.createReadyChild(caller = rootId)
            fake(childId).info.update { it.copy(isPausable = false) }

            val result = repo.pause(rootId)

            result.shouldBeInstanceOf<WorkspaceAction.Pause.Result.Refused>()
            result.reason shouldBe WorkspaceAction.Pause.Reason.NOT_PAUSABLE
        }

    @Test
    fun `pausing a unit swaps every member in a single publish`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val rootId = repo.createReadyTab(Workspace.Type.APPS)
        val childId = repo.createReadyChild(caller = rootId)

        val pausedSnapshots = mutableListOf<Set<Workspace.Id>>()
        repo.state
            .onEach { state -> pausedSnapshots += state.infos.filter { it.isPaused }.map { it.id }.toSet() }
            .launchIn(backgroundScope)
        testScheduler.runCurrent()
        pausedSnapshots.clear()

        val result = repo.pause(rootId)

        result.shouldBeInstanceOf<WorkspaceAction.Pause.Result.Success>()
        result.id shouldBe rootId
        result.pausedIds shouldBe listOf(rootId, childId)
        testScheduler.runCurrent()
        // A per-member pause would publish the root's stand-in on its own first
        pausedSnapshots.none { it.size == 1 } shouldBe true
        pausedSnapshots.last() shouldBe setOf(rootId, childId)
        fake(rootId).released shouldBe true
        fake(childId).released shouldBe true
    }

    @Test
    fun `resuming a unit rebuilds the owner before what it owns`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val rootId = repo.createReadyTab(Workspace.Type.APPS)
        val childId = repo.createReadyChild(caller = rootId)
        repo.pause(rootId).shouldBeInstanceOf<WorkspaceAction.Pause.Result.Success>()

        val result = repo.execute(WorkspaceAction.Resume(rootId))

        result.shouldBeInstanceOf<WorkspaceAction.Resume.Result.Success>()
        result.outcomes shouldBe mapOf(
            rootId to WorkspaceAction.Resume.MemberOutcome.Resumed,
            childId to WorkspaceAction.Resume.MemberOutcome.Resumed,
        )
        // A modal has nothing to bind to while its owner is still a stand-in
        createdWorkspaces.map { it.id }.drop(2) shouldBe listOf(rootId, childId)
        repo.infoFor(rootId).isPaused shouldBe false
        repo.infoFor(childId).isPaused shouldBe false
    }

    @Test
    fun `a member failing to capture its arguments leaves the whole unit untouched`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val rootId = repo.createReadyTab(Workspace.Type.APPS)
            val childId = repo.createReadyChild(caller = rootId)
            val boom = IllegalStateException("Cannot serialize state")
            fake(childId).argumentsError = boom

            val result = repo.pause(rootId)

            result.shouldBeInstanceOf<WorkspaceAction.Pause.Result.Failed>()
            result.error shouldBe boom
            repo.infoFor(rootId).isPaused shouldBe false
            repo.infoFor(childId).isPaused shouldBe false
            fake(rootId).released shouldBe false
            fake(childId).released shouldBe false
        }

    @Test
    fun `a guard flipping while a member is captured leaves the whole unit untouched`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val rootId = repo.createReadyTab(Workspace.Type.APPS)
            val childId = repo.createReadyChild(caller = rootId)
            val child = fake(childId)
            child.whileCapturingArguments = {
                child.info.update { it.copy(operationCount = 1) }
            }

            val result = repo.pause(rootId)

            result.shouldBeInstanceOf<WorkspaceAction.Pause.Result.Refused>()
            result.reason shouldBe WorkspaceAction.Pause.Reason.BUSY
            repo.infoFor(rootId).isPaused shouldBe false
            fake(rootId).released shouldBe false
            child.released shouldBe false
        }

    @Test
    fun `a failing owner leaves its descendants paused and reports them as skipped`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val rootId = repo.createReadyTab(Workspace.Type.APPS)
            val childId = repo.createReadyChild(caller = rootId)
            repo.pause(rootId)
            val boom = IllegalStateException("Factory exploded")
            createFailures[rootId] = boom

            val result = repo.execute(WorkspaceAction.Resume(rootId))

            result.shouldBeInstanceOf<WorkspaceAction.Resume.Result.Failed>()
            result.error shouldBe boom
            result.outcomes[rootId] shouldBe WorkspaceAction.Resume.MemberOutcome.Failed(boom)
            result.outcomes[childId] shouldBe
                WorkspaceAction.Resume.MemberOutcome.SkippedAncestorFailed(rootId, boom)
            repo.infoFor(rootId).lifecycleState shouldBe Workspace.LifecycleState.Paused(boom)
            // Skipped, NOT failed: the child never had a chance to break
            repo.infoFor(childId).lifecycleState shouldBe Workspace.LifecycleState.Paused()
        }

    @Test
    fun `a failing member in the middle only skips its own subtree`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val rootId = repo.createReadyTab(Workspace.Type.APPS)
        val childId = repo.createReadyChild(caller = rootId)
        val grandChildId = repo.createReadyChild(caller = childId)
        val siblingId = repo.createReadyChild(caller = rootId)
        repo.pause(rootId)
        val boom = IllegalStateException("Factory exploded")
        // The root resumes fine; the failure lands on the member in the middle
        createFailures[childId] = boom

        val result = repo.execute(WorkspaceAction.Resume(rootId))

        result.shouldBeInstanceOf<WorkspaceAction.Resume.Result.Success>()
        result.outcomes[rootId] shouldBe WorkspaceAction.Resume.MemberOutcome.Resumed
        result.outcomes[childId] shouldBe WorkspaceAction.Resume.MemberOutcome.Failed(boom)
        result.outcomes[grandChildId] shouldBe
            WorkspaceAction.Resume.MemberOutcome.SkippedAncestorFailed(childId, boom)
        result.outcomes[siblingId] shouldBe WorkspaceAction.Resume.MemberOutcome.Resumed
        repo.infoFor(rootId).isPaused shouldBe false
        repo.infoFor(siblingId).isPaused shouldBe false
        repo.infoFor(grandChildId).lifecycleState shouldBe Workspace.LifecycleState.Paused()
    }

    @Test
    fun `pausing and resuming a child id acts on its whole unit`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val rootId = repo.createReadyTab(Workspace.Type.APPS)
        val childId = repo.createReadyChild(caller = rootId)

        val paused = repo.pause(childId)

        paused.shouldBeInstanceOf<WorkspaceAction.Pause.Result.Success>()
        paused.id shouldBe rootId
        repo.infoFor(rootId).isPaused shouldBe true
        repo.infoFor(childId).isPaused shouldBe true

        repo.execute(WorkspaceAction.Resume(childId))
            .shouldBeInstanceOf<WorkspaceAction.Resume.Result.Success>()

        repo.infoFor(rootId).isPaused shouldBe false
        repo.infoFor(childId).isPaused shouldBe false
    }

    @Test
    fun `a cycle in the caller graph is refused instead of hanging`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val idA = Workspace.Id()
        val idB = Workspace.Id()
        // Nothing validates caller ids at creation, so this topology is reachable
        repo.execute(
            WorkspaceAction.Create(
                type = Workspace.Type.APPS,
                arguments = FakeChildArguments(Workspace.Type.APPS, callerWorkspaceId = idB),
                id = idA,
            )
        ).shouldBeInstanceOf<WorkspaceAction.Create.Result.Success>()
        repo.execute(
            WorkspaceAction.Create(
                type = Workspace.Type.APPS,
                arguments = FakeChildArguments(Workspace.Type.APPS, callerWorkspaceId = idA),
                id = idB,
            )
        ).shouldBeInstanceOf<WorkspaceAction.Create.Result.Success>()
        fake(idA).markReady()
        fake(idB).markReady()

        val result = withTimeout(10.seconds) { repo.pause(idA) }

        result.shouldBeInstanceOf<WorkspaceAction.Pause.Result.Refused>()
        result.reason shouldBe WorkspaceAction.Pause.Reason.BROKEN_OWNERSHIP
        withTimeout(10.seconds) {
            repo.execute(WorkspaceAction.Resume(idA)) shouldBe WorkspaceAction.Resume.Result.NoOp
        }
    }

    @Test
    fun `pausing a workspace holding a content claim is refused and keeps the claim`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val id = repo.createReadyTab(Workspace.Type.EDITOR)
            repo.execute(
                WorkspaceAction.ClaimContentPath(Workspace.Type.EDITOR, pathA, id)
            ) shouldBe WorkspaceAction.ClaimContentPath.Result.Granted

            val result = repo.pause(id)

            result.shouldBeInstanceOf<WorkspaceAction.Pause.Result.Refused>()
            result.reason shouldBe WorkspaceAction.Pause.Reason.CLAIM_HELD
            // Dropping the claim could let a duplicate tab open on that path mid-transition
            val create = repo.execute(contentReq(pathA))
            create.shouldBeInstanceOf<WorkspaceAction.Create.Result.AlreadyOpen>()
            create.existingId shouldBe id
        }

    @Test
    fun `pausing a busy workspace is refused`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val id = repo.createReadyTab()
        fake(id).info.update { it.copy(operationCount = 1) }

        val result = repo.pause(id)

        result.shouldBeInstanceOf<WorkspaceAction.Pause.Result.Refused>()
        result.reason shouldBe WorkspaceAction.Pause.Reason.BUSY
    }

    @Test
    fun `pausing a workspace needing attention is refused`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val id = repo.createReadyTab()
        fake(id).info.update { it.copy(attentionCount = 1) }

        val result = repo.pause(id)

        result.shouldBeInstanceOf<WorkspaceAction.Pause.Result.Refused>()
        result.reason shouldBe WorkspaceAction.Pause.Reason.BUSY
    }

    @Test
    fun `pausing a workspace with unsaved changes is refused`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val id = repo.createReadyTab()
        fake(id).info.update { it.copy(hasUnsavedChanges = true) }

        val result = repo.pause(id)

        result.shouldBeInstanceOf<WorkspaceAction.Pause.Result.Refused>()
        result.reason shouldBe WorkspaceAction.Pause.Reason.UNSAVED_CHANGES
    }

    @Test
    fun `pausing a workspace that opted out is refused`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val id = repo.createReadyTab()
        fake(id).info.update { it.copy(isPausable = false) }

        val result = repo.pause(id)

        result.shouldBeInstanceOf<WorkspaceAction.Pause.Result.Refused>()
        result.reason shouldBe WorkspaceAction.Pause.Reason.NOT_PAUSABLE
    }

    @Test
    fun `pausing a workspace that is not ready is refused`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val id = repo.createTab()

        val result = repo.pause(id)

        result.shouldBeInstanceOf<WorkspaceAction.Pause.Result.Refused>()
        result.reason shouldBe WorkspaceAction.Pause.Reason.NOT_READY
    }

    @Test
    fun `a guard flipping while arguments are captured refuses without mutating`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = createRepo()
            val id = repo.createReadyTab()
            val workspace = fake(id)
            workspace.whileCapturingArguments = {
                workspace.info.update { it.copy(hasUnsavedChanges = true) }
            }

            val result = repo.pause(id)

            result.shouldBeInstanceOf<WorkspaceAction.Pause.Result.Refused>()
            result.reason shouldBe WorkspaceAction.Pause.Reason.UNSAVED_CHANGES
            repo.infoFor(id).isPaused shouldBe false
            workspace.released shouldBe false
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

    @Test
    fun `creating a tab tracks its type as used`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()

        repo.createTab(type = Workspace.Type.EXPLORER)

        coVerify(exactly = 1) { usageRepo.track(Workspace.Type.EXPLORER, any()) }
    }

    @Test
    fun `batch creation tracks every successfully created tab`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = true)

        repo.createBatch(
            createReq(Workspace.Type.EXPLORER),
            createReq(Workspace.Type.SEARCHER),
        )

        coVerify(exactly = 1) { usageRepo.track(Workspace.Type.EXPLORER, any()) }
        coVerify(exactly = 1) { usageRepo.track(Workspace.Type.SEARCHER, any()) }
    }

    @Test
    fun `batch creation does not track already-open entries`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = true)
        repo.createContentTab(pathA)

        // pathA resolves to AlreadyOpen, only pathB is actually created
        repo.createBatch(contentReq(pathA), contentReq(pathB))

        // One for the initial tab, one for pathB — the AlreadyOpen entry adds nothing
        coVerify(exactly = 2) { usageRepo.track(Workspace.Type.EDITOR, any()) }
    }

    @Test
    fun `a batch of only already-open entries tracks nothing`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = true)
        repo.createContentTab(pathA)
        repo.createContentTab(pathB)
        clearMocks(usageRepo, answers = false)

        repo.createBatch(contentReq(pathA), contentReq(pathB))

        coVerify(exactly = 0) { usageRepo.track(any(), any()) }
    }

    @Test
    fun `batch creation does not track failed creates`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val sharedId = Workspace.Id()

        repo.createBatch(
            createReq(Workspace.Type.EXPLORER, id = sharedId),
            createReq(Workspace.Type.SEARCHER, id = sharedId),
        )

        // The colliding SEARCHER create fails, so only the successful EXPLORER counts
        coVerify(exactly = 1) { usageRepo.track(Workspace.Type.EXPLORER, any()) }
        coVerify(exactly = 0) { usageRepo.track(Workspace.Type.SEARCHER, any()) }
    }

    @Test
    fun `deferred duplicates in a batch track exactly one use`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = true)

        // Both requests target the same content path, so the second is deferred to AlreadyOpen
        repo.createBatch(contentReq(pathA), contentReq(pathA))

        coVerify(exactly = 1) { usageRepo.track(Workspace.Type.EDITOR, any()) }
    }

    @Test
    fun `batch creation does not track limit-skipped requests`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo(isPro = false)
        repeat(WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT) { repo.createTab(type = Workspace.Type.EXPLORER) }

        repo.createBatch(createReq(Workspace.Type.SEARCHER))

        coVerify(exactly = 0) { usageRepo.track(Workspace.Type.SEARCHER, any()) }
    }

    @Test
    fun `session restore does not track usage`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()

        repo.execute(
            WorkspaceAction.Create(
                type = Workspace.Type.EXPLORER,
                arguments = FakeArguments(Workspace.Type.EXPLORER),
                skipLimitCheck = true,
            )
        ).shouldBeInstanceOf<WorkspaceAction.Create.Result.Success>()

        coVerify(exactly = 0) { usageRepo.track(any(), any()) }
    }

    @Test
    fun `sub-workspaces do not track usage`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val parentId = repo.createTab(type = Workspace.Type.EXPLORER)

        repo.createSubWorkspace(caller = parentId, type = Workspace.Type.SEARCHER)

        coVerify(exactly = 0) { usageRepo.track(Workspace.Type.SEARCHER, any()) }
    }

    @Test
    fun `quota-exempt types do not track usage`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()

        repo.createTab(type = Workspace.Type.DEVELOPER)

        coVerify(exactly = 0) { usageRepo.track(any(), any()) }
    }

    @Test
    fun `the templates picker does not track usage`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()

        repo.createTab(type = Workspace.Type.TEMPLATES)

        coVerify(exactly = 0) { usageRepo.track(any(), any()) }
    }

    @Test
    fun `morphing a tab tracks the new type`() = runTest(UnconfinedTestDispatcher()) {
        val repo = createRepo()
        val originalId = repo.createTab(type = Workspace.Type.EXPLORER)

        repo.execute(
            WorkspaceAction.Create(
                type = Workspace.Type.SEARCHER,
                arguments = FakeArguments(Workspace.Type.SEARCHER),
                replace = originalId,
            )
        ).shouldBeInstanceOf<WorkspaceAction.Create.Result.Success>()

        coVerify(exactly = 1) { usageRepo.track(Workspace.Type.SEARCHER, any()) }
    }
}
