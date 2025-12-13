package eu.darken.butler.workspace.ui.session

import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceFactory
import eu.darken.butler.workspace.core.WorkspaceRepo
import eu.darken.butler.workspace.core.WorkspaceSettings
import eu.darken.butler.workspace.core.session.WorkspaceSessionStorage
import eu.darken.butler.workspace.ui.WorkspacePageManager
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
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
}
