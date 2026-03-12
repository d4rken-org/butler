package eu.darken.butler.workspace.ui.session

import androidx.room.withTransaction
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceFactory
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.WorkspaceRepo
import eu.darken.butler.workspace.core.WorkspaceSettings
import eu.darken.butler.workspace.core.session.WorkspaceSessionStorage
import eu.darken.butler.workspace.core.session.db.WorkspaceInstanceEntity
import eu.darken.butler.workspace.core.session.db.WorkspaceSessionDatabase
import eu.darken.butler.workspace.ui.WorkspacePageManager
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

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
            every { workspaceRepo.retrieve(id) } returns flowOf(ws)
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

            // Workspace B disappears between state read and retrieve
            every { workspaceRepo.retrieve(wsIdB) } returns flowOf(null)

            sessionManager.saveSession()

            // Only A should be saved
            upsertedEntities.size shouldBe 1
            upsertedEntities.single().workspaceId shouldBe wsIdA
        }
    }
}
