package eu.darken.butler.workspace.ui

import android.os.Parcel
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.undo.ClosedWorkspaceMember
import eu.darken.butler.workspace.core.undo.ClosedWorkspacePlacement
import eu.darken.butler.workspace.core.undo.ClosedWorkspaceRestoreTicket
import eu.darken.butler.workspace.core.undo.ClosedWorkspaceSnapshot
import eu.darken.butler.workspace.core.undo.ClosedWorkspaceStash
import eu.darken.butler.workspace.ui.floatingbar.WorkspaceBarCollapseStates
import eu.darken.butler.workspace.ui.restore.WorkspaceViewPrefs
import eu.darken.butler.workspace.ui.scroll.WorkspaceScrollPosition
import eu.darken.butler.workspace.ui.scroll.WorkspaceScrollPositions
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * The UI half of an undo: capturing where a closing unit was, and putting a restored one back.
 *
 * The two capture points race by design - the repo publishes the workspace list before it emits the
 * close event - so both orderings are exercised here rather than assumed.
 */
class WorkspaceUndoPlacementTest : BaseTest() {

    private class FakeArguments(
        override val type: Workspace.Type,
    ) : Workspace.Arguments {
        override fun describeContents(): Int = 0
        override fun writeToParcel(dest: Parcel, flags: Int) = Unit
    }

    private lateinit var workspaceRemote: WorkspaceRemote
    private lateinit var eventsFlow: MutableSharedFlow<WorkspaceEvent>
    private lateinit var stateFlow: MutableStateFlow<WorkspaceRemote.State>
    private lateinit var testScope: TestScope
    private lateinit var stash: ClosedWorkspaceStash
    private lateinit var scrollPositions: WorkspaceScrollPositions
    private lateinit var pageManager: WorkspacePageManager

    @BeforeEach
    fun setup() {
        eventsFlow = MutableSharedFlow()
        stateFlow = MutableStateFlow(WorkspaceRemote.State())
        workspaceRemote = mockk {
            every { state } returns stateFlow
            every { events } returns eventsFlow
        }
        testScope = TestScope(UnconfinedTestDispatcher())
        stash = ClosedWorkspaceStash(testScope)
        scrollPositions = WorkspaceScrollPositions()
        pageManager = WorkspacePageManager(
            appScope = testScope,
            workspaceRemote = workspaceRemote,
            scrollPositions = scrollPositions,
            barCollapseStates = WorkspaceBarCollapseStates(),
            viewPrefs = WorkspaceViewPrefs(),
            closedStash = stash,
        )
    }

    private fun infoOf(
        id: Workspace.Id,
        callerWorkspaceId: Workspace.Id? = null,
    ) = Workspace.Info(
        id = id,
        type = Workspace.Type.EXPLORER,
        title = "Workspace ${id.shortTag}".toCaString(),
        callerWorkspaceId = callerWorkspaceId,
    )

    private fun publishInfos(vararg ids: Workspace.Id) {
        stateFlow.value = WorkspaceRemote.State(infos = ids.map { infoOf(it) })
    }

    private fun snapshotOf(closeToken: Long, members: List<Workspace.Id>) = ClosedWorkspaceSnapshot(
        members = members.mapIndexed { index, id ->
            ClosedWorkspaceMember(
                id = id,
                type = Workspace.Type.EXPLORER,
                arguments = FakeArguments(Workspace.Type.EXPLORER),
                createdAt = null,
                customTitle = null,
                automaticTitle = "Tab ${id.shortTag}".toCaString(),
                automaticSubtitle = null,
                callerWorkspaceId = members.getOrNull(index - 1)?.takeIf { index > 0 },
            )
        },
        unitOrderIndex = 0,
        precedingNeighbourIds = emptyList(),
        followingNeighbourIds = emptyList(),
        closeToken = closeToken,
        baselineContentHolders = emptyMap(),
        baselineSingletonOccupants = null,
    )

    /** Everything the repo would contribute, so [ClosedWorkspaceStash.peekEntry] can be read. */
    private fun completeEntry(closeToken: Long, members: List<Workspace.Id>) {
        stash.commitIdentity(snapshotOf(closeToken, members))
        stash.markDestructionComplete(closeToken)
    }

    private suspend fun emitClosed(id: Workspace.Id, closeToken: Long?) {
        eventsFlow.emit(WorkspaceEvent.Closed(workspaceId = id, closeToken = closeToken))
        testScope.testScheduler.runCurrent()
    }

    // ==================== Capturing ====================

    @Test
    fun `the close event captures pane and focus`() = runTest(UnconfinedTestDispatcher()) {
        val closing = Workspace.Id()
        val other = Workspace.Id()
        publishInfos(closing, other)
        pageManager.setPaneCount(1)
        pageManager.setLayout(mapOf(0 to closing), focusedId = closing)

        val token = stash.nextToken()
        stash.armClose(token, closing, setOf(closing))
        emitClosed(closing, token)
        completeEntry(token, listOf(closing))

        stash.peekEntry()!!.placement shouldBe ClosedWorkspacePlacement(paneIndex = 0, focusedMemberId = closing)
    }

    @Test
    fun `the cleanup collector captures pane and focus when it runs first`() =
        runTest(UnconfinedTestDispatcher()) {
            val closing = Workspace.Id()
            val other = Workspace.Id()
            publishInfos(closing, other)
            pageManager.setPaneCount(2)
            pageManager.setLayout(mapOf(0 to other, 1 to closing), focusedId = closing)

            val token = stash.nextToken()
            stash.armClose(token, closing, setOf(closing))
            // The repo publishes the list before it emits the event, so this can happen first
            publishInfos(other)
            testScope.testScheduler.runCurrent()
            emitClosed(closing, token)
            completeEntry(token, listOf(closing))

            stash.peekEntry()!!.placement shouldBe ClosedWorkspacePlacement(paneIndex = 1, focusedMemberId = closing)
        }

    @Test
    fun `the second capture point never overwrites the first`() = runTest(UnconfinedTestDispatcher()) {
        val closing = Workspace.Id()
        val other = Workspace.Id()
        publishInfos(closing, other)
        pageManager.setPaneCount(2)
        pageManager.setLayout(mapOf(0 to other, 1 to closing), focusedId = closing)

        val token = stash.nextToken()
        stash.armClose(token, closing, setOf(closing))
        emitClosed(closing, token)
        // Whatever the cleanup collector sees afterwards is already the damaged state
        publishInfos(other)
        testScope.testScheduler.runCurrent()
        completeEntry(token, listOf(closing))

        stash.peekEntry()!!.placement shouldBe ClosedWorkspacePlacement(paneIndex = 1, focusedMemberId = closing)
    }

    @Test
    fun `a close in the background is captured as unfocused`() = runTest(UnconfinedTestDispatcher()) {
        val closing = Workspace.Id()
        val focused = Workspace.Id()
        publishInfos(closing, focused)
        pageManager.setPaneCount(2)
        pageManager.setLayout(mapOf(0 to focused, 1 to closing), focusedId = focused)

        val token = stash.nextToken()
        stash.armClose(token, closing, setOf(closing))
        emitClosed(closing, token)
        completeEntry(token, listOf(closing))

        stash.peekEntry()!!.placement shouldBe ClosedWorkspacePlacement(paneIndex = 1, focusedMemberId = null)
    }

    @Test
    fun `focus that is already gone falls back to the unit's newest member`() =
        runTest(UnconfinedTestDispatcher()) {
            val closing = Workspace.Id()
            publishInfos(closing)
            pageManager.setPaneCount(1)
            pageManager.setLayout(mapOf(0 to closing), focusedId = closing)
            pageManager.setFocusedWorkspace(null)

            val token = stash.nextToken()
            stash.armClose(token, closing, setOf(closing))
            emitClosed(closing, token)
            completeEntry(token, listOf(closing))

            stash.peekEntry()!!.placement shouldBe ClosedWorkspacePlacement(paneIndex = 0, focusedMemberId = closing)
        }

    @Test
    fun `every member contributes its own view state`() = runTest(UnconfinedTestDispatcher()) {
        val root = Workspace.Id()
        val child = Workspace.Id()
        stateFlow.value = WorkspaceRemote.State(
            infos = listOf(infoOf(root), infoOf(child, callerWorkspaceId = root)),
        )
        pageManager.setPaneCount(1)
        pageManager.setLayout(mapOf(0 to root), focusedId = root)
        scrollPositions.record(scrollPositions.positionFor(child, "list"), scrollPosition())

        val token = stash.nextToken()
        stash.armClose(token, root, setOf(root, child))
        // Children close first, and each forgets its own slots as it goes
        emitClosed(child, token)
        emitClosed(root, token)
        completeEntry(token, listOf(root, child))

        val entry = stash.peekEntry()!!
        entry.slots.keys shouldBe setOf(root, child)
        entry.slots.getValue(child).scrollPositions shouldBe mapOf("list" to scrollPosition())
    }

    @Test
    fun `a close without a token captures nothing`() = runTest(UnconfinedTestDispatcher()) {
        val closing = Workspace.Id()
        publishInfos(closing)
        pageManager.setPaneCount(1)
        pageManager.setLayout(mapOf(0 to closing), focusedId = closing)

        emitClosed(closing, closeToken = null)

        stash.feedback.value shouldBe null
        pageManager.state.value.selectedWorkspaces shouldBe emptyMap()
    }

    // ==================== Restoring ====================

    private fun restore(
        rootId: Workspace.Id,
        paneIndex: Int?,
        focusedMemberId: Workspace.Id?,
    ): Long {
        val token = stash.stampIncarnation(rootId)
        stash.armRestoreTicket(
            ClosedWorkspaceRestoreTicket(
                rootId = rootId,
                restoreToken = token,
                slots = emptyMap(),
                placement = ClosedWorkspacePlacement(paneIndex, focusedMemberId),
            )
        )
        return token
    }

    @Test
    fun `a focused restore takes pane 0 on a single-pane layout`() = runTest(UnconfinedTestDispatcher()) {
        val restored = Workspace.Id()
        val other = Workspace.Id()
        publishInfos(other, restored)
        pageManager.setPaneCount(1)
        pageManager.setLayout(mapOf(0 to other), focusedId = other)

        restore(restored, paneIndex = 0, focusedMemberId = restored)
        pageManager.applyRestoreTicket(restored)

        pageManager.state.value.selectedWorkspaces shouldBe mapOf(0 to restored)
        pageManager.state.value.focusedWorkspaceId shouldBe restored
    }

    @Test
    fun `a background restore takes its old pane when it is free`() = runTest(UnconfinedTestDispatcher()) {
        val restored = Workspace.Id()
        val other = Workspace.Id()
        publishInfos(other, restored)
        pageManager.setPaneCount(2)
        pageManager.setLayout(mapOf(0 to other), focusedId = other)

        restore(restored, paneIndex = 1, focusedMemberId = null)
        pageManager.applyRestoreTicket(restored)

        pageManager.state.value.selectedWorkspaces shouldBe mapOf(0 to other, 1 to restored)
        // Nothing was focused by this: the tab was closed in the background
        pageManager.state.value.focusedWorkspaceId shouldBe other
    }

    @Test
    fun `a background restore into an occupied pane stays unassigned`() = runTest(UnconfinedTestDispatcher()) {
        val restored = Workspace.Id()
        val a = Workspace.Id()
        val b = Workspace.Id()
        publishInfos(a, b, restored)
        pageManager.setPaneCount(2)
        pageManager.setLayout(mapOf(0 to a, 1 to b), focusedId = a)

        restore(restored, paneIndex = 1, focusedMemberId = null)
        pageManager.applyRestoreTicket(restored)

        pageManager.state.value.selectedWorkspaces shouldBe mapOf(0 to a, 1 to b)
        pageManager.state.value.focusedWorkspaceId shouldBe a
    }

    @Test
    fun `a focused restore into an occupied pane evicts for itself`() = runTest(UnconfinedTestDispatcher()) {
        val restored = Workspace.Id()
        val a = Workspace.Id()
        val b = Workspace.Id()
        publishInfos(a, b, restored)
        pageManager.setPaneCount(2)
        pageManager.setLayout(mapOf(0 to a, 1 to b), focusedId = a)

        restore(restored, paneIndex = 1, focusedMemberId = restored)
        pageManager.applyRestoreTicket(restored)

        // A focused workspace with no pane would be invisible
        pageManager.state.value.selectedWorkspaces.containsValue(restored) shouldBe true
        pageManager.state.value.focusedWorkspaceId shouldBe restored
    }

    @Test
    fun `a missing pane index degrades to a background restore`() = runTest(UnconfinedTestDispatcher()) {
        val restored = Workspace.Id()
        val other = Workspace.Id()
        publishInfos(other, restored)
        pageManager.setPaneCount(2)
        pageManager.setLayout(mapOf(0 to other), focusedId = other)

        restore(restored, paneIndex = null, focusedMemberId = null)
        pageManager.applyRestoreTicket(restored)

        // Neither capture point saw a pane, so there is no target to claim
        pageManager.state.value.selectedWorkspaces shouldBe mapOf(0 to other)
        pageManager.state.value.focusedWorkspaceId shouldBe other
    }

    @Test
    fun `a pane index the layout no longer has is clamped`() = runTest(UnconfinedTestDispatcher()) {
        val restored = Workspace.Id()
        publishInfos(restored)
        pageManager.setPaneCount(1)

        restore(restored, paneIndex = 3, focusedMemberId = null)
        pageManager.applyRestoreTicket(restored)

        pageManager.state.value.selectedWorkspaces shouldBe mapOf(0 to restored)
    }

    @Test
    fun `the ticket is applied once, whoever gets there first`() = runTest(UnconfinedTestDispatcher()) {
        val restored = Workspace.Id()
        val other = Workspace.Id()
        publishInfos(other, restored)
        pageManager.setPaneCount(2)
        pageManager.setLayout(mapOf(0 to other), focusedId = other)

        val token = restore(restored, paneIndex = 1, focusedMemberId = null)
        pageManager.applyRestoreTicket(restored, token)

        // The backstop finds nothing left to do, so it cannot re-place a tab the user has moved on
        pageManager.awaitAndApplyRestore(restored)
        stash.takeRestoreTicket(restored, token) shouldBe null
        pageManager.state.value.selectedWorkspaces shouldBe mapOf(0 to other, 1 to restored)
    }

    @Test
    fun `a replayed pre-close emission cannot strip a restored tab`() = runTest(UnconfinedTestDispatcher()) {
        val restored = Workspace.Id()
        val other = Workspace.Id()
        publishInfos(other, restored)
        pageManager.setPaneCount(2)
        pageManager.setLayout(mapOf(0 to other), focusedId = other)

        val token = restore(restored, paneIndex = 1, focusedMemberId = restored)
        pageManager.applyRestoreTicket(restored, token)

        // The close's own state emission, arriving after the restore already published. The id it
        // omits is the one the undo just brought back, and the ticket that placed it is spent.
        publishInfos(other)
        testScope.testScheduler.runCurrent()

        pageManager.state.value.selectedWorkspaces shouldBe mapOf(0 to other, 1 to restored)
        pageManager.state.value.focusedWorkspaceId shouldBe restored
    }

    @Test
    fun `a tab that really closed still loses its pane and focus`() = runTest(UnconfinedTestDispatcher()) {
        val closing = Workspace.Id()
        stash.stampIncarnation(closing)
        publishInfos(closing)
        pageManager.setPaneCount(1)
        pageManager.setLayout(mapOf(0 to closing), focusedId = closing)

        // What the repo does as part of a close, before it publishes the shortened list
        stash.dropIncarnation(closing)
        publishInfos()
        testScope.testScheduler.runCurrent()

        pageManager.state.value.selectedWorkspaces shouldBe emptyMap()
        pageManager.state.value.focusedWorkspaceId shouldBe null
    }

    @Test
    fun `a restore wait gives up once its incarnation is gone`() = runTest(UnconfinedTestDispatcher()) {
        val restored = Workspace.Id()
        val token = restore(restored, paneIndex = 0, focusedMemberId = restored)
        // Closed again before its state emission ever listed it: the ticket is void and nothing
        // will ever name the id, so waiting on presence alone would never return.
        stash.dropIncarnation(restored)

        pageManager.awaitAndApplyRestore(restored, token)

        pageManager.state.value.selectedWorkspaces shouldBe emptyMap()
    }

    @Test
    fun `the backstop places a restore whose event never arrived`() = runTest(UnconfinedTestDispatcher()) {
        val restored = Workspace.Id()
        publishInfos(restored)
        pageManager.setPaneCount(1)

        restore(restored, paneIndex = 0, focusedMemberId = restored)
        // No Created event at all: the emission died with the caller that asked for the undo
        pageManager.awaitAndApplyRestore(restored)

        pageManager.state.value.selectedWorkspaces shouldBe mapOf(0 to restored)
        pageManager.state.value.focusedWorkspaceId shouldBe restored
    }

    private fun scrollPosition() = WorkspaceScrollPosition(index = 7, offset = 3)
}
