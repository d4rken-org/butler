package eu.darken.butler.workspace.ui.session

import android.os.Parcel
import androidx.room.withTransaction
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceFactory
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.WorkspaceRepo
import eu.darken.butler.workspace.core.WorkspaceSettings
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import eu.darken.butler.workspace.core.session.WorkspaceSessionStorage
import eu.darken.butler.workspace.core.session.WorkspaceSessionStorage.Companion.DEFAULT_SESSION_ID
import eu.darken.butler.workspace.core.session.db.WorkspaceInstanceEntity
import eu.darken.butler.workspace.core.session.db.WorkspaceSessionDatabase
import eu.darken.butler.workspace.core.session.db.WorkspaceSessionEntity
import eu.darken.butler.workspace.core.session.db.WorkspaceUIState
import eu.darken.butler.workspace.ui.WorkspacePageManager
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant

class WorkspaceSessionManagerTest : BaseTest() {

    private lateinit var workspaceSettings: WorkspaceSettings
    private lateinit var workspaceRepo: WorkspaceRepo
    private lateinit var workspacePageManager: WorkspacePageManager
    private lateinit var storage: WorkspaceSessionStorage
    private lateinit var json: Json
    private lateinit var factoryMap: Map<Workspace.Type, WorkspaceFactory<*>>
    private lateinit var testScope: TestScope
    private lateinit var sessionManager: WorkspaceSessionManager

    // Captured arguments from mock
    private var capturedFocusedId: Workspace.Id? = null
    private var capturedSelections: Map<Int, Workspace.Id> = emptyMap()

    @BeforeEach
    fun setup() {
        capturedFocusedId = null
        capturedSelections = emptyMap()

        workspaceSettings = mockk(relaxed = true)
        workspaceRepo = mockk(relaxed = true)
        workspacePageManager = mockk(relaxed = true)
        storage = mockk(relaxed = true)
        json = Json
        factoryMap = emptyMap()
        testScope = TestScope(UnconfinedTestDispatcher())

        // Provide a valid state flow for findReplacementWorkspace
        every { workspacePageManager.state } returns MutableStateFlow(WorkspacePageManager.State())

        // Set up capture for applyRestoredUIState using answer block
        coEvery {
            workspacePageManager.applyRestoredUIState(any(), any())
        } answers {
            capturedFocusedId = firstArg()
            capturedSelections = secondArg()
        }

        sessionManager = WorkspaceSessionManager(
            appScope = testScope,
            workspaceSettings = workspaceSettings,
            workspaceRepo = workspaceRepo,
            workspacePageManager = workspacePageManager,
            storage = storage,
            json = json,
            factoryMap = factoryMap,
        )
    }

    @Test
    fun `applyUIState - focused workspace already in pane 0 - no change`() = runTest {
        val wsA = Workspace.Id()
        val wsB = Workspace.Id()

        sessionManager.applyUIState(
            focusedId = wsA,
            selectedIds = mapOf(0 to wsA, 1 to wsB),
            actualWorkspaceIds = listOf(wsA, wsB),
        )

        coVerify { workspacePageManager.applyRestoredUIState(any(), any()) }

        capturedFocusedId shouldBe wsA
        capturedSelections shouldBe mapOf(0 to wsA, 1 to wsB)
    }

    @Test
    fun `applyUIState - focused workspace in pane 1 - no duplicate created`() = runTest {
        val wsA = Workspace.Id()
        val wsB = Workspace.Id()

        // wsA is focused but in pane 1, wsB is in pane 0
        sessionManager.applyUIState(
            focusedId = wsA,
            selectedIds = mapOf(0 to wsB, 1 to wsA),
            actualWorkspaceIds = listOf(wsA, wsB),
        )

        coVerify { workspacePageManager.applyRestoredUIState(any(), any()) }

        capturedFocusedId shouldBe wsA
        // Key assertion: wsA should NOT be duplicated to pane 0
        capturedSelections shouldBe mapOf(0 to wsB, 1 to wsA)
        // Verify no duplicate IDs in values
        capturedSelections.values.toSet().size shouldBe capturedSelections.values.size
    }

    @Test
    fun `applyUIState - focused workspace not in any pane - assigned to pane 0`() = runTest {
        val wsA = Workspace.Id()
        val wsB = Workspace.Id()
        val wsC = Workspace.Id()

        // wsA is focused but not in any pane selection
        sessionManager.applyUIState(
            focusedId = wsA,
            selectedIds = mapOf(0 to wsB, 1 to wsC),
            actualWorkspaceIds = listOf(wsA, wsB, wsC),
        )

        coVerify { workspacePageManager.applyRestoredUIState(any(), any()) }

        capturedFocusedId shouldBe wsA
        // wsA should be assigned to pane 0 (replacing wsB)
        capturedSelections[0] shouldBe wsA
        capturedSelections[1] shouldBe wsC
    }

    @Test
    fun `applyUIState - invalid focused workspace falls back to first available`() = runTest {
        val wsA = Workspace.Id()
        val wsB = Workspace.Id()
        val invalidWs = Workspace.Id()

        sessionManager.applyUIState(
            focusedId = invalidWs,
            selectedIds = mapOf(0 to wsA),
            actualWorkspaceIds = listOf(wsA, wsB),
        )

        coVerify { workspacePageManager.applyRestoredUIState(any(), any()) }

        // Falls back to first available workspace
        capturedFocusedId shouldBe wsA
    }

    @Test
    fun `applyUIState - empty pane selections - focused workspace assigned to pane 0`() = runTest {
        val wsA = Workspace.Id()

        sessionManager.applyUIState(
            focusedId = wsA,
            selectedIds = emptyMap(),
            actualWorkspaceIds = listOf(wsA),
        )

        coVerify { workspacePageManager.applyRestoredUIState(any(), any()) }

        capturedFocusedId shouldBe wsA
        capturedSelections shouldBe mapOf(0 to wsA)
    }

    @Test
    fun `applyUIState - duplicate in saved panes - handled by usedIds tracking`() = runTest {
        val wsA = Workspace.Id()
        val wsB = Workspace.Id()

        // Saved state has duplicate (wsA in both panes) - this shouldn't happen but we should handle it
        sessionManager.applyUIState(
            focusedId = wsA,
            selectedIds = mapOf(0 to wsA, 1 to wsA),
            actualWorkspaceIds = listOf(wsA, wsB),
        )

        coVerify { workspacePageManager.applyRestoredUIState(any(), any()) }

        // usedIds tracking should prevent the duplicate, and find a replacement for pane 1
        val values = capturedSelections.values.toList()
        values.toSet().size shouldBe values.size // No duplicates
    }

    @Test
    fun `applyUIState - pane count reduced scenario - focused visible in remaining pane`() = runTest {
        val wsA = Workspace.Id()
        val wsB = Workspace.Id()

        // Simulating landscape -> portrait: pane 2 no longer exists
        // Saved state had wsA in pane 2, wsB in pane 0
        // After restoration, wsA should still be visible (assigned to pane 0 if not in any pane)
        sessionManager.applyUIState(
            focusedId = wsA,
            selectedIds = mapOf(0 to wsB, 2 to wsA), // pane 1 missing, pane 2 might not exist anymore
            actualWorkspaceIds = listOf(wsA, wsB),
        )

        coVerify { workspacePageManager.applyRestoredUIState(any(), any()) }

        capturedFocusedId shouldBe wsA
        // wsA should be visible somewhere - either pane 0 or pane 2
        capturedSelections.values.toSet() shouldBe setOf(wsA, wsB)
        // No duplicates
        capturedSelections.values.toSet().size shouldBe capturedSelections.values.size
    }

    @Nested
    inner class SaveSession {

        private val wsIdA = Workspace.Id()
        private val wsIdB = Workspace.Id()
        private val wsIdC = Workspace.Id()

        private val repoStateFlow = MutableStateFlow(WorkspaceRemote.State())
        private val upsertedEntities = mutableListOf<WorkspaceInstanceEntity>()
        private val deletedIds = mutableListOf<List<Workspace.Id>>()

        private lateinit var mockFactory: WorkspaceFactory<Workspace.Arguments>

        // Per-workspace arguments, mutable so tests can change them
        private val workspaceArgs = mutableMapOf<Workspace.Id, Workspace.Arguments>()
        private val defaultArgs = mockk<Workspace.Arguments>().also {
            every { it.type } returns Workspace.Type.EXPLORER
        }

        // Track IDs the DAO reports as existing (for removed workspace detection)
        private var existingDaoIds = listOf<Workspace.Id>()

        @BeforeEach
        fun setupSave() {
            mockkStatic("androidx.room.RoomDatabaseKt")

            val mockDatabase = mockk<WorkspaceSessionDatabase>(relaxed = true)
            every { storage.database } returns mockDatabase
            coEvery { mockDatabase.withTransaction(any<suspend () -> Any?>()) } coAnswers {
                @Suppress("UNCHECKED_CAST")
                val block = args[1] as suspend () -> Any?
                block()
            }

            // Capture upserted workspace entities
            coEvery { storage.dao.upsertWorkspace(any()) } coAnswers {
                upsertedEntities.add(firstArg())
            }

            // Capture deleted workspace IDs
            coEvery { storage.dao.deleteWorkspacesByIds(any()) } coAnswers {
                deletedIds.add(firstArg())
            }

            // Return existing IDs for removed workspace detection
            coEvery { storage.dao.getWorkspaceIds(any()) } coAnswers { existingDaoIds }

            // Mock repo state
            every { workspaceRepo.state } returns repoStateFlow

            // Mock factory for serialization (uses args identity for distinct hashes)
            @Suppress("UNCHECKED_CAST")
            mockFactory = mockk<WorkspaceFactory<Workspace.Arguments>>()
            every { mockFactory.serialize(any(), any()) } answers {
                JsonPrimitive(secondArg<Workspace.Arguments>().hashCode().toString())
            }

            // Register default workspaces
            registerWorkspace(wsIdA)
            registerWorkspace(wsIdB)
            registerWorkspace(wsIdC)

            factoryMap = mapOf(Workspace.Type.EXPLORER to mockFactory)
            sessionManager = WorkspaceSessionManager(
                appScope = testScope,
                workspaceSettings = workspaceSettings,
                workspaceRepo = workspaceRepo,
                workspacePageManager = workspacePageManager,
                storage = storage,
                json = json,
                factoryMap = factoryMap,
            )
        }

        private fun registerWorkspace(id: Workspace.Id, args: Workspace.Arguments = defaultArgs) {
            workspaceArgs[id] = args
            val ws = mockk<Workspace<Workspace.Arguments>>()
            every { ws.id } returns id
            coEvery { ws.createArguments() } answers { workspaceArgs[id]!! }
            every { workspaceRepo.peek(id) } returns ws
        }

        @AfterEach
        fun teardownSave() {
            upsertedEntities.clear()
            deletedIds.clear()
        }

        private fun makeInfo(
            id: Workspace.Id,
            callerWorkspaceId: Workspace.Id? = null,
        ) = Workspace.Info(
            id = id,
            type = Workspace.Type.EXPLORER,
            title = "Workspace".toCaString(),
            callerWorkspaceId = callerWorkspaceId,
        )

        @Test
        fun `reorder updates orderIndex even when arguments unchanged`() = runTest {
            // Initial order: A, B, C
            repoStateFlow.value = WorkspaceRemote.State(
                infos = listOf(makeInfo(wsIdA), makeInfo(wsIdB), makeInfo(wsIdC)),
            )
            sessionManager.saveSession()

            upsertedEntities.size shouldBe 3
            upsertedEntities.single { it.workspaceId == wsIdA }.orderIndex shouldBe 0
            upsertedEntities.single { it.workspaceId == wsIdB }.orderIndex shouldBe 1
            upsertedEntities.single { it.workspaceId == wsIdC }.orderIndex shouldBe 2

            upsertedEntities.clear()

            // Reorder to: B, A, C (only position changed, arguments identical)
            repoStateFlow.value = WorkspaceRemote.State(
                infos = listOf(makeInfo(wsIdB), makeInfo(wsIdA), makeInfo(wsIdC)),
            )
            sessionManager.saveSession()

            // A and B should be re-saved with new orderIndex, C unchanged
            upsertedEntities.size shouldBe 2
            upsertedEntities.single { it.workspaceId == wsIdB }.orderIndex shouldBe 0
            upsertedEntities.single { it.workspaceId == wsIdA }.orderIndex shouldBe 1
        }

        @Test
        fun `unchanged state does not trigger unnecessary upserts`() = runTest {
            repoStateFlow.value = WorkspaceRemote.State(
                infos = listOf(makeInfo(wsIdA), makeInfo(wsIdB)),
            )
            sessionManager.saveSession()
            upsertedEntities.size shouldBe 2

            upsertedEntities.clear()

            // Save again with identical state
            sessionManager.saveSession()
            upsertedEntities.size shouldBe 0
        }

        @Test
        fun `argument change triggers upsert for affected workspace only`() = runTest {
            repoStateFlow.value = WorkspaceRemote.State(
                infos = listOf(makeInfo(wsIdA), makeInfo(wsIdB)),
            )
            sessionManager.saveSession()
            upsertedEntities.size shouldBe 2

            upsertedEntities.clear()

            // Change arguments for workspace B only
            val newArgs = mockk<Workspace.Arguments>()
            every { newArgs.type } returns Workspace.Type.EXPLORER
            workspaceArgs[wsIdB] = newArgs

            sessionManager.saveSession()

            upsertedEntities.size shouldBe 1
            upsertedEntities.single().workspaceId shouldBe wsIdB
            upsertedEntities.single().orderIndex shouldBe 1
        }

        @Test
        fun `sub-workspaces are excluded from save`() = runTest {
            val subWsId = Workspace.Id()
            registerWorkspace(subWsId)

            repoStateFlow.value = WorkspaceRemote.State(
                infos = listOf(
                    makeInfo(wsIdA),
                    makeInfo(subWsId, callerWorkspaceId = wsIdA),
                    makeInfo(wsIdB),
                ),
            )
            sessionManager.saveSession()

            // Only A and B saved, sub-workspace excluded
            upsertedEntities.size shouldBe 2
            upsertedEntities.map { it.workspaceId }.toSet() shouldBe setOf(wsIdA, wsIdB)
            // B should be at index 1 (sub-workspace doesn't count)
            upsertedEntities.single { it.workspaceId == wsIdB }.orderIndex shouldBe 1
        }

        @Test
        fun `removed workspace is deleted from database`() = runTest {
            repoStateFlow.value = WorkspaceRemote.State(
                infos = listOf(makeInfo(wsIdA), makeInfo(wsIdB), makeInfo(wsIdC)),
            )
            sessionManager.saveSession()

            upsertedEntities.clear()

            // Simulate: DAO reports A, B, C exist, but current state only has A, C
            existingDaoIds = listOf(wsIdA, wsIdB, wsIdC)
            repoStateFlow.value = WorkspaceRemote.State(
                infos = listOf(makeInfo(wsIdA), makeInfo(wsIdC)),
            )
            sessionManager.saveSession()

            // B should be deleted
            deletedIds.size shouldBe 1
            deletedIds.single() shouldBe listOf(wsIdB)

            // A unchanged (same args, same index), C re-saved (index changed from 2 to 1)
            upsertedEntities.size shouldBe 1
            upsertedEntities.single { it.workspaceId == wsIdC }.orderIndex shouldBe 1
        }

        @Test
        fun `disappeared workspace during save is skipped gracefully`() = runTest {
            repoStateFlow.value = WorkspaceRemote.State(
                infos = listOf(makeInfo(wsIdA), makeInfo(wsIdB)),
            )

            // Workspace B disappears between the state read and the instance lookup
            every { workspaceRepo.peek(wsIdB) } returns null

            sessionManager.saveSession()

            // Only A should be saved
            upsertedEntities.size shouldBe 1
            upsertedEntities.single().workspaceId shouldBe wsIdA
        }
    }

    @Nested
    inner class OnDemandRestore {

        private val idA = Workspace.Id()
        private val idB = Workspace.Id()
        private val idC = Workspace.Id()

        private val createAttempts = mutableListOf<Workspace.Id>()
        private val createdIds = mutableListOf<Workspace.Id>()
        private val failingIds = mutableSetOf<Workspace.Id>()
        private val upsertedEntities = mutableListOf<WorkspaceInstanceEntity>()
        private val onDemandFlow = MutableStateFlow(true)

        // Restoration and the repo need live coroutines; the outer setup's manager dies on its
        // fully-relaxed mocks and cancels the shared testScope.
        private lateinit var restoreScope: TestScope
        private lateinit var repo: WorkspaceRepo
        private lateinit var pageManager: WorkspacePageManager

        @BeforeEach
        fun setupOnDemand() {
            restoreScope = TestScope(UnconfinedTestDispatcher())
            createAttempts.clear()
            createdIds.clear()
            failingIds.clear()
            upsertedEntities.clear()
            onDemandFlow.value = true

            mockkStatic("androidx.room.RoomDatabaseKt")
            val mockDatabase = mockk<WorkspaceSessionDatabase>(relaxed = true)
            every { storage.database } returns mockDatabase
            coEvery { mockDatabase.withTransaction(any<suspend () -> Any?>()) } coAnswers {
                @Suppress("UNCHECKED_CAST")
                val block = args[1] as suspend () -> Any?
                block()
            }
            coEvery { storage.dao.upsertWorkspace(any()) } coAnswers { upsertedEntities.add(firstArg()) }
            coEvery { storage.dao.getWorkspaceIds(any()) } returns emptyList()

            every { workspaceSettings.sessionRestoreEnabled } returns mockk {
                every { flow } returns flowOf(true)
            }
            every { workspaceSettings.restoreWorkspacesOnDemand } returns mockk {
                every { flow } returns onDemandFlow
            }
            every { workspaceSettings.layoutModePortrait } returns mockk {
                every { flow } returns flowOf(WorkspacePanelMode.AUTO)
            }
            every { workspaceSettings.layoutModeLandscape } returns mockk {
                every { flow } returns flowOf(WorkspacePanelMode.AUTO)
            }

            factoryMap = Workspace.Type.entries.associateWith { type ->
                FakeSessionFactory(type) { id ->
                    createAttempts += id
                    if (id in failingIds) throw IllegalStateException("Cannot create $id")
                    createdIds += id
                }
            }

            val upgradeInfo = mockk<UpgradeRepo.Info>().apply { every { isUpgraded } returns true }
            val upgradeRepo = mockk<UpgradeRepo>().apply { every { this@apply.upgradeInfo } returns flowOf(upgradeInfo) }
            repo = WorkspaceRepo(
                appScope = restoreScope,
                factoryMap = factoryMap,
                workspaceSettings = workspaceSettings,
                operationsManager = mockk(relaxed = true),
                upgradeRepo = upgradeRepo,
            )
            pageManager = WorkspacePageManager(appScope = restoreScope, workspaceRemote = repo)
        }

        private fun createManager() = WorkspaceSessionManager(
            appScope = restoreScope,
            workspaceSettings = workspaceSettings,
            workspaceRepo = repo,
            workspacePageManager = pageManager,
            storage = storage,
            json = json,
            factoryMap = factoryMap,
        )

        private fun session(
            focusedId: Workspace.Id?,
            paneSelections: Map<Int, Workspace.Id> = emptyMap(),
        ) = WorkspaceSessionEntity(
            sessionId = DEFAULT_SESSION_ID,
            label = "Test Session",
            createdAt = Instant.DISTANT_PAST,
            uiState = WorkspaceUIState(focusedWorkspaceId = focusedId, paneSelections = paneSelections),
        )

        private fun entity(
            id: Workspace.Id,
            orderIndex: Int,
            tag: String,
            type: Workspace.Type = Workspace.Type.EXPLORER,
        ) = WorkspaceInstanceEntity(
            workspaceId = id,
            sessionId = DEFAULT_SESSION_ID,
            type = type,
            orderIndex = orderIndex,
            createdAt = Instant.DISTANT_PAST,
            lastModified = Instant.DISTANT_PAST,
            arguments = JsonPrimitive(tag).toString(),
        )

        private fun savedSession(
            focusedId: Workspace.Id?,
            paneSelections: Map<Int, Workspace.Id> = emptyMap(),
            entities: List<WorkspaceInstanceEntity> = listOf(
                entity(idA, 0, "a"),
                entity(idB, 1, "b"),
                entity(idC, 2, "c"),
            ),
        ) {
            coEvery { storage.dao.getSession(any()) } returns session(focusedId, paneSelections)
            coEvery { storage.dao.getWorkspaces(any()) } returns entities
        }

        private suspend fun workspaceIds(): List<Workspace.Id> = repo.state.first().infos.map { it.id }

        private suspend fun dormantIds(): List<Workspace.Id> =
            repo.state.first().infos.filter { it.isDormant }.map { it.id }

        @Test
        fun `only the focused workspace is created, the rest stay dormant`() =
            runTest(UnconfinedTestDispatcher()) {
                savedSession(focusedId = idB)

                val manager = createManager()
                restoreScope.testScheduler.runCurrent()

                manager.state.value shouldBe WorkspaceSessionManager.State.Restored(listOf(idA, idB, idC))
                createAttempts shouldBe listOf(idB)
                workspaceIds() shouldBe listOf(idA, idB, idC)
                dormantIds() shouldBe listOf(idA, idC)
            }

        @Test
        fun `an unknown saved focus falls back to the first candidate being created`() =
            runTest(UnconfinedTestDispatcher()) {
                savedSession(focusedId = Workspace.Id())

                createManager()
                restoreScope.testScheduler.runCurrent()

                createAttempts shouldBe listOf(idA)
                dormantIds() shouldBe listOf(idB, idC)
                pageManager.state.value.focusedWorkspaceId shouldBe idA
            }

        @Test
        fun `a dropped focus candidate falls back to the first surviving candidate`() =
            runTest(UnconfinedTestDispatcher()) {
                // idB is a duplicate singleton row and gets deduped away during validation
                savedSession(
                    focusedId = idB,
                    entities = listOf(
                        entity(idA, 0, "a", type = Workspace.Type.DEVELOPER),
                        entity(idB, 1, "b", type = Workspace.Type.DEVELOPER),
                        entity(idC, 2, "c"),
                    ),
                )

                createManager()
                restoreScope.testScheduler.runCurrent()

                createAttempts shouldBe listOf(idA)
                workspaceIds() shouldBe listOf(idA, idC)
                pageManager.state.value.focusedWorkspaceId shouldBe idA
            }

        @Test
        fun `a dormant workspace is promoted when the focused one cannot be created`() =
            runTest(UnconfinedTestDispatcher()) {
                failingIds += idB
                savedSession(focusedId = idB)

                createManager()
                restoreScope.testScheduler.runCurrent()

                // idB failed, so the first dormant one takes the focused slot
                createAttempts shouldBe listOf(idB, idA)
                createdIds shouldBe listOf(idA)
                workspaceIds() shouldBe listOf(idA, idC)
                dormantIds() shouldBe listOf(idC)
                pageManager.state.value.focusedWorkspaceId shouldBe idA
            }

        @Test
        fun `with on-demand restore disabled every workspace is created`() =
            runTest(UnconfinedTestDispatcher()) {
                onDemandFlow.value = false
                savedSession(focusedId = idB)

                createManager()
                restoreScope.testScheduler.runCurrent()

                createAttempts shouldBe listOf(idA, idB, idC)
                dormantIds() shouldHaveSize 0
            }

        @Test
        fun `a stale focus during restoration hydrates nothing`() =
            runTest(UnconfinedTestDispatcher()) {
                // Simulates the focus surviving in the SavedStateHandle from the previous process
                pageManager.applyRestoredUIState(idA, mapOf(0 to idA))
                savedSession(focusedId = idB)

                createManager()
                restoreScope.testScheduler.runCurrent()

                createAttempts shouldBe listOf(idB)
                dormantIds() shouldBe listOf(idA, idC)
            }

        @Test
        fun `focusing a dormant workspace after restoration hydrates it`() =
            runTest(UnconfinedTestDispatcher()) {
                savedSession(focusedId = idB)
                createManager()
                restoreScope.testScheduler.runCurrent()

                pageManager.setLayout(mapOf(0 to idC), focusedId = idC)
                restoreScope.testScheduler.runCurrent()

                createAttempts shouldBe listOf(idB, idC)
                dormantIds() shouldBe listOf(idA)
            }

        @Test
        fun `a workspace that failed to hydrate is not retried on focus`() =
            runTest(UnconfinedTestDispatcher()) {
                failingIds += idC
                savedSession(focusedId = idB)
                createManager()
                restoreScope.testScheduler.runCurrent()

                pageManager.setLayout(mapOf(0 to idC), focusedId = idC)
                restoreScope.testScheduler.runCurrent()
                pageManager.setLayout(mapOf(0 to idB), focusedId = idB)
                restoreScope.testScheduler.runCurrent()
                pageManager.setLayout(mapOf(0 to idC), focusedId = idC)
                restoreScope.testScheduler.runCurrent()

                createAttempts.count { it == idC } shouldBe 1
                repo.state.first().infos.single { it.id == idC }.lifecycleState
                    .shouldBeInstanceOf<Workspace.LifecycleState.Dormant>()
            }

        @Test
        fun `the seeded save cache keeps an unchanged session from being rewritten`() =
            runTest(UnconfinedTestDispatcher()) {
                savedSession(focusedId = idB)
                val manager = createManager()
                restoreScope.testScheduler.runCurrent()

                manager.saveSession()

                upsertedEntities shouldHaveSize 0
            }

        @Test
        fun `saving keeps the arguments of dormant and live workspaces`() =
            runTest(UnconfinedTestDispatcher()) {
                savedSession(focusedId = idB)
                val manager = createManager()
                restoreScope.testScheduler.runCurrent()

                // Reordering changes every stored index, forcing all three rows to be re-saved
                repo.execute(WorkspaceAction.Reorder(listOf(idB, idC, idA)))
                manager.saveSession()

                upsertedEntities shouldHaveSize 3
                // idB was created during restore, idA and idC are still dormant stand-ins
                upsertedEntities.single { it.workspaceId == idB }.let {
                    it.arguments shouldBe JsonPrimitive("b").toString()
                    it.orderIndex shouldBe 0
                }
                upsertedEntities.single { it.workspaceId == idC }.let {
                    it.arguments shouldBe JsonPrimitive("c").toString()
                    it.orderIndex shouldBe 1
                }
                upsertedEntities.single { it.workspaceId == idA }.let {
                    it.arguments shouldBe JsonPrimitive("a").toString()
                    it.orderIndex shouldBe 2
                }
            }
    }

    @Nested
    inner class AutoSaveGuard {

        private val wsId = Workspace.Id()
        private val repoStateFlow = MutableStateFlow(WorkspaceRemote.State())
        private val upsertedEntities = mutableListOf<WorkspaceInstanceEntity>()

        // The outer setup constructs a manager whose init coroutine fails on the fully-relaxed
        // mocks, cancelling the shared testScope. These tests need live coroutines, so they
        // get their own scope.
        private lateinit var autoSaveScope: TestScope

        @BeforeEach
        fun setupAutoSave() {
            autoSaveScope = TestScope(UnconfinedTestDispatcher())
            mockkStatic("androidx.room.RoomDatabaseKt")

            val mockDatabase = mockk<WorkspaceSessionDatabase>(relaxed = true)
            every { storage.database } returns mockDatabase
            coEvery { mockDatabase.withTransaction(any<suspend () -> Any?>()) } coAnswers {
                @Suppress("UNCHECKED_CAST")
                val block = args[1] as suspend () -> Any?
                block()
            }

            coEvery { storage.dao.upsertWorkspace(any()) } coAnswers {
                upsertedEntities.add(firstArg())
            }
            coEvery { storage.dao.getWorkspaceIds(any()) } returns emptyList()

            every { workspaceRepo.state } returns repoStateFlow

            every { workspaceSettings.sessionRestoreEnabled } returns mockk {
                every { flow } returns flowOf(true)
            }

            val mockFactory = mockk<WorkspaceFactory<Workspace.Arguments>>()
            every { mockFactory.serialize(any(), any()) } returns JsonPrimitive("args")
            factoryMap = mapOf(Workspace.Type.EXPLORER to mockFactory)

            val args = mockk<Workspace.Arguments>().also {
                every { it.type } returns Workspace.Type.EXPLORER
            }
            val ws = mockk<Workspace<Workspace.Arguments>>()
            every { ws.id } returns wsId
            coEvery { ws.createArguments() } returns args
            every { workspaceRepo.peek(wsId) } returns ws
        }

        @AfterEach
        fun teardownAutoSave() {
            upsertedEntities.clear()
        }

        private fun createManager() = WorkspaceSessionManager(
            appScope = autoSaveScope,
            workspaceSettings = workspaceSettings,
            workspaceRepo = workspaceRepo,
            workspacePageManager = workspacePageManager,
            storage = storage,
            json = json,
            factoryMap = factoryMap,
        )

        private fun makeInfo(id: Workspace.Id) = Workspace.Info(
            id = id,
            type = Workspace.Type.EXPLORER,
            title = "Workspace".toCaString(),
        )

        @Test
        fun `auto-save runs after successful restore`() = runTest {
            // No saved session -> restore succeeds with empty result
            coEvery { storage.dao.getSession(any()) } returns null

            val manager = createManager()
            autoSaveScope.testScheduler.runCurrent()
            manager.state.value shouldBe WorkspaceSessionManager.State.Restored(emptyList())

            repoStateFlow.value = WorkspaceRemote.State(infos = listOf(makeInfo(wsId)))
            autoSaveScope.testScheduler.advanceTimeBy(1000)
            autoSaveScope.testScheduler.runCurrent()

            // Control: proves the auto-save pipeline is live in this test setup
            coVerify { storage.dao.upsertSession(any()) }
            upsertedEntities.single().workspaceId shouldBe wsId
        }

        @Test
        fun `failed restore does not wipe the saved session via auto-save`() = runTest {
            // Restore blows up after session load, outside the per-row handling (e.g. Room
            // row mapper failure on a corrupt/unknown value). getSession itself succeeds so
            // that an unguarded saveSession would get far enough to delete rows.
            coEvery { storage.dao.getSession(any()) } returns mockk(relaxed = true)
            coEvery { storage.dao.getWorkspaces(any()) } throws RuntimeException("row mapping failed")
            // The DB still holds a row that no open workspace matches — without the guard,
            // auto-save would delete it.
            val staleId = Workspace.Id()
            coEvery { storage.dao.getWorkspaceIds(any()) } returns listOf(staleId)

            val manager = createManager()
            autoSaveScope.testScheduler.runCurrent()
            manager.state.value.shouldBeInstanceOf<WorkspaceSessionManager.State.Error>()

            repoStateFlow.value = WorkspaceRemote.State(infos = listOf(makeInfo(wsId)))
            autoSaveScope.testScheduler.advanceTimeBy(1000)
            autoSaveScope.testScheduler.runCurrent()

            // Verify against the dao child mock directly — chained storage.dao.x verification
            // would also count the dao getter access from the restore attempt itself.
            val dao = storage.dao
            coVerify(exactly = 0) { dao.upsertSession(any()) }
            coVerify(exactly = 0) { dao.deleteWorkspacesByIds(any()) }
            coVerify(exactly = 0) { dao.upsertWorkspace(any()) }
        }
    }
}

private data class FakeSessionArguments(
    override val type: Workspace.Type,
    val tag: String,
) : Workspace.Arguments {
    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: Parcel, flags: Int) = Unit
}

private class FakeSessionWorkspace(
    override val id: Workspace.Id,
    private val arguments: Workspace.Arguments,
) : Workspace<Workspace.Arguments> {
    override val type: Workspace.Type = arguments.type

    override val info = MutableStateFlow(
        Workspace.Info(
            id = id,
            type = type,
            title = "Fake $type".toCaString(),
            lifecycleState = Workspace.LifecycleState.Ready,
        )
    )

    override suspend fun createArguments(): Workspace.Arguments = arguments
}

private class FakeSessionFactory(
    private val type: Workspace.Type,
    private val onCreate: (Workspace.Id) -> Unit,
) : WorkspaceFactory<Workspace.Arguments> {

    override fun create(id: Workspace.Id, arguments: Workspace.Arguments): Workspace<Workspace.Arguments> {
        onCreate(id)
        return FakeSessionWorkspace(id, arguments)
    }

    override val argumentsSerializer: KSerializer<Workspace.Arguments>
        get() = throw NotImplementedError("serialize/deserialize are overridden directly")

    override fun serialize(json: Json, arguments: Workspace.Arguments): JsonElement =
        JsonPrimitive((arguments as FakeSessionArguments).tag)

    override fun deserialize(json: Json, element: JsonElement): Workspace.Arguments =
        FakeSessionArguments(type, element.jsonPrimitive.content)
}
