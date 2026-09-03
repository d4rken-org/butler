package eu.darken.butler.workspace.core

import android.os.Parcel
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.undo.ClosedWorkspaceStash
import eu.darken.butler.workspace.ui.WorkspacePageManager
import eu.darken.butler.workspace.ui.floatingbar.WorkspaceBarCollapseStates
import eu.darken.butler.workspace.ui.restore.WorkspaceViewPrefs
import eu.darken.butler.workspace.ui.scroll.WorkspaceScrollPosition
import eu.darken.butler.workspace.ui.scroll.WorkspaceScrollPositions
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * Undoing a tab close, end to end: the repo captures and restores, the page manager contributes the
 * UI half. Both are real here - the entry is only offered once both halves and the close's own
 * teardown have landed, so a repo-only harness could never produce one.
 */
class WorkspaceUndoCloseTest : BaseTest() {

    private class FakeArguments(
        override val type: Workspace.Type,
    ) : Workspace.Arguments {
        override fun describeContents(): Int = 0
        override fun writeToParcel(dest: Parcel, flags: Int) = Unit
    }

    private class FakeChildArguments(
        override val type: Workspace.Type,
        override val callerWorkspaceId: Workspace.Id?,
        override val pausableAsChild: Boolean = true,
    ) : Workspace.ArgumentsWithCaller {
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

        /** What [createArguments] returns; set to simulate state drifting away from creation args. */
        var currentArguments: Workspace.Arguments = arguments

        /** Stands in for a type whose live state is invisible in its info (Searcher's query). */
        var fingerprint: Any? = null

        /** Runs inside [createArguments], i.e. while the repo has released its lock. */
        var whileCapturingArguments: (suspend () -> Unit)? = null

        override val info = MutableStateFlow(
            Workspace.Info(
                id = id,
                type = type,
                title = "Fake $type".toCaString(),
                lifecycleState = Workspace.LifecycleState.Ready,
                callerWorkspaceId = (arguments as? Workspace.ArgumentsWithCaller)?.callerWorkspaceId,
                pausableAsChild = arguments.isPausableAsChild,
                contentPath = (arguments as? Workspace.ArgumentsWithContentPath)?.contentPath,
            )
        )

        override val restorableStateFingerprint: Any?
            get() = fingerprint

        override suspend fun createArguments(): Workspace.Arguments {
            whileCapturingArguments?.invoke()
            return currentArguments
        }
    }

    private val createdWorkspaces = mutableListOf<FakeWorkspace>()

    private inner class FakeFactory : WorkspaceFactory<Workspace.Arguments> {
        override fun create(id: Workspace.Id, arguments: Workspace.Arguments): Workspace<Workspace.Arguments> =
            FakeWorkspace(id, arguments).also { createdWorkspaces += it }

        override fun deriveDisplay(arguments: Workspace.Arguments): WorkspaceDisplay? = null

        override val argumentsSerializer: KSerializer<Workspace.Arguments>
            get() = throw NotImplementedError("serialize/deserialize are overridden directly")

        override fun serialize(json: Json, arguments: Workspace.Arguments): JsonElement = JsonNull

        override fun deserialize(json: Json, element: JsonElement): Workspace.Arguments =
            FakeArguments(Workspace.Type.EXPLORER)
    }

    private val undoEnabled = MutableStateFlow(true)

    private val workspaceSettings: WorkspaceSettings = mockk<WorkspaceSettings>(relaxed = true).apply {
        every { layoutModePortrait.flow } returns flowOf(WorkspacePanelMode.AUTO)
        every { layoutModeLandscape.flow } returns flowOf(WorkspacePanelMode.AUTO)
        every { undoCloseEnabled.flow } returns undoEnabled
    }

    private lateinit var scope: TestScope
    private lateinit var stash: ClosedWorkspaceStash
    private lateinit var repo: WorkspaceRepo
    private lateinit var pageManager: WorkspacePageManager
    private lateinit var scrollPositions: WorkspaceScrollPositions
    private lateinit var operationsManager: OperationsManager

    @BeforeEach
    fun setup() {
        createdWorkspaces.clear()
        undoEnabled.value = true
        scope = TestScope(UnconfinedTestDispatcher())
        stash = ClosedWorkspaceStash(scope)
        scrollPositions = WorkspaceScrollPositions()
        operationsManager = mockk(relaxed = true)

        val upgradeInfo = mockk<UpgradeRepo.Info>().apply {
            every { isPro } returns false
            every { isSettled } returns true
            every { error } returns null
        }
        val upgradeRepo = mockk<UpgradeRepo>().apply {
            every { this@apply.upgradeInfo } returns MutableStateFlow(upgradeInfo)
            coEvery { refresh() } just Runs
        }
        repo = WorkspaceRepo(
            appScope = scope,
            factoryMap = Workspace.Type.entries.associateWith { FakeFactory() },
            workspaceSettings = workspaceSettings,
            operationsManager = operationsManager,
            upgradeRepo = upgradeRepo,
            usageRepo = mockk(relaxed = true),
            closedStash = stash,
        )
        pageManager = WorkspacePageManager(
            appScope = scope,
            workspaceRemote = repo,
            scrollPositions = scrollPositions,
            barCollapseStates = WorkspaceBarCollapseStates(),
            viewPrefs = WorkspaceViewPrefs(),
            closedStash = stash,
        )
    }

    /** Lets the event collectors run without advancing virtual time past the undo window. */
    private fun settle() = scope.testScheduler.runCurrent()

    private suspend fun createTab(
        type: Workspace.Type = Workspace.Type.EXPLORER,
        source: Workspace.Id? = null,
    ): Workspace.Id {
        val result = repo.execute(
            WorkspaceAction.Create(type = type, arguments = FakeArguments(type), sourceWorkspaceId = source)
        )
        settle()
        return (result as WorkspaceAction.Create.Result.Success).newId
    }

    private suspend fun createChild(caller: Workspace.Id): Workspace.Id {
        val type = Workspace.Type.APP_DETAILS
        val result = repo.execute(
            WorkspaceAction.Create(type = type, arguments = FakeChildArguments(type, caller))
        )
        settle()
        return (result as WorkspaceAction.Create.Result.Success).newId
    }

    private suspend fun closeUndoable(id: Workspace.Id) {
        repo.execute(WorkspaceAction.Close(id, undoable = true))
        settle()
    }

    private suspend fun undo(): WorkspaceAction.UndoClose.Result {
        val result = repo.undoLastClose().await()
        settle()
        return result
    }

    private suspend fun openIds(): List<Workspace.Id> = repo.state.first().infos.map { it.id }

    private fun fake(id: Workspace.Id): FakeWorkspace = createdWorkspaces.last { it.id == id }

    @Test
    fun `an undone close brings the tab back where it was`() = runTest(UnconfinedTestDispatcher()) {
        val first = createTab()
        val closed = createTab()
        val last = createTab()

        closeUndoable(closed)
        openIds() shouldBe listOf(first, last)
        stash.feedback.value shouldNotBe null

        undo().shouldBeInstanceOf<WorkspaceAction.UndoClose.Result.Success>()

        openIds() shouldBe listOf(first, closed, last)
        stash.feedback.value shouldBe null
    }

    /**
     * Undo keeps deciding placement from the neighbours it captured, not from where a create would
     * have put the tab. The order has to be built by anchored creates up front: any create after the
     * close is a foreign mutation that drops the entry, so "close, create, undo" can never test this.
     */
    @Test
    fun `an undone close ignores where a create would have placed the tab`() =
        runTest(UnconfinedTestDispatcher()) {
            val first = createTab()
            val closed = createTab(source = first)
            val last = createTab(source = closed)
            openIds() shouldBe listOf(first, closed, last)

            closeUndoable(closed)
            undo().shouldBeInstanceOf<WorkspaceAction.UndoClose.Result.Success>()

            openIds() shouldBe listOf(first, closed, last)
        }

    @Test
    fun `the restored tab holds the arguments it was closed with`() = runTest(UnconfinedTestDispatcher()) {
        val id = createTab()
        val navigated = FakeArguments(Workspace.Type.EXPLORER)
        fake(id).currentArguments = navigated

        closeUndoable(id)
        undo()

        // The stand-in reports the captured arguments, not the ones the tab was created with
        repo.peek(id)?.createArguments() shouldBe navigated
    }

    @Test
    fun `a tab with stacked children comes back with them`() = runTest(UnconfinedTestDispatcher()) {
        val root = createTab()
        val child = createChild(root)
        val grandChild = createChild(child)

        closeUndoable(root)
        openIds() shouldBe emptyList()

        undo()

        openIds() shouldBe listOf(root, child, grandChild)
        repo.peekStacks().unitOf(root)?.map { it.id } shouldBe listOf(root, child, grandChild)
    }

    @Test
    fun `the view state of every member survives the close`() = runTest(UnconfinedTestDispatcher()) {
        val root = createTab()
        val child = createChild(root)
        val childLease = scrollPositions.positionFor(child, "list")
        scrollPositions.record(childLease, WorkspaceScrollPosition(index = 12, offset = 34))

        closeUndoable(root)
        scrollPositions.positionFor(child, "list").saved shouldBe null

        undo()

        // Contributed per member: a single snapshot at the root's event would already have missed it
        scrollPositions.positionFor(child, "list").saved shouldBe WorkspaceScrollPosition(index = 12, offset = 34)
    }

    // ==================== Eligibility ====================

    @Test
    fun `closing a stacked child is not undoable`() = runTest(UnconfinedTestDispatcher()) {
        val root = createTab()
        val child = createChild(root)

        closeUndoable(child)

        stash.feedback.value shouldBe null
        undo() shouldBe WorkspaceAction.UndoClose.Result.Unavailable
        openIds() shouldBe listOf(root)
    }

    @Test
    fun `a tab holding a picker is not undoable`() = runTest(UnconfinedTestDispatcher()) {
        val root = createTab()
        repo.execute(
            WorkspaceAction.Create(
                type = Workspace.Type.EXPLORER,
                arguments = FakePickerArguments(Workspace.Type.EXPLORER, root),
            )
        )
        settle()

        closeUndoable(root)

        stash.feedback.value shouldBe null
        openIds() shouldBe emptyList()
    }

    @Test
    fun `the setting off closes without stashing anything`() = runTest(UnconfinedTestDispatcher()) {
        undoEnabled.value = false
        val id = createTab()

        closeUndoable(id)

        stash.feedback.value shouldBe null
        stash.peekStashedArguments() shouldBe emptyList()
        openIds() shouldBe emptyList()
    }

    // ==================== The unlock window ====================

    @Test
    fun `a same-id replacement while capturing aborts the capture`() = runTest(UnconfinedTestDispatcher()) {
        val id = createTab()
        fake(id).whileCapturingArguments = {
            repo.execute(
                WorkspaceAction.Create(
                    type = Workspace.Type.EXPLORER,
                    arguments = FakeArguments(Workspace.Type.EXPLORER),
                    replace = id,
                    id = id,
                )
            )
        }

        closeUndoable(id)

        // The instance that was captured is not the one that got closed, so there is nothing honest
        // to offer - but the close itself still happened.
        stash.feedback.value shouldBe null
        openIds() shouldBe emptyList()
    }

    @Test
    fun `a tab navigating while capturing aborts the capture`() = runTest(UnconfinedTestDispatcher()) {
        val id = createTab()
        val movedTo = LocalPath.build("/storage/emulated/0/Music")
        fake(id).whileCapturingArguments = {
            fake(id).info.value = fake(id).info.value.copy(contentPath = movedTo)
        }

        closeUndoable(id)

        stash.feedback.value shouldBe null
        openIds() shouldBe emptyList()
    }

    @Test
    fun `a query edited while capturing aborts the capture`() = runTest(UnconfinedTestDispatcher()) {
        val id = createTab(Workspace.Type.SEARCHER)
        fake(id).fingerprint = "cats"
        fake(id).whileCapturingArguments = {
            // A Searcher republishes only its identity, so this is invisible in its info
            fake(id).fingerprint = "dogs"
        }

        closeUndoable(id)

        stash.feedback.value shouldBe null
        openIds() shouldBe emptyList()
    }

    @Test
    fun `a second close inside the capture window is the one that stays offered`() =
        runTest(UnconfinedTestDispatcher()) {
            val first = createTab()
            val second = createTab()
            fake(first).whileCapturingArguments = {
                // Two closes in quick succession: the second one runs start to finish while the
                // first is still capturing, so both are in flight at the same time
                repo.execute(WorkspaceAction.Close(second, undoable = true))
            }

            closeUndoable(first)

            openIds() shouldBe emptyList()
            // Latest wins, and the older close's own removals must not take the newer entry down
            stash.feedback.value shouldNotBe null
            undo().shouldBeInstanceOf<WorkspaceAction.UndoClose.Result.Success>()
            openIds() shouldBe listOf(second)
        }

    @Test
    fun `a tab that turns dirty while capturing asks before closing`() = runTest(UnconfinedTestDispatcher()) {
        val id = createTab(Workspace.Type.EDITOR)
        fake(id).whileCapturingArguments = {
            fake(id).info.value = fake(id).info.value.copy(hasUnsavedChanges = true)
        }

        closeUndoable(id)

        // Not closed, and not silently undoable either: the discard question must still be asked
        openIds() shouldBe listOf(id)
        stash.feedback.value shouldBe null
        val confirmation = repo.pendingConfirmations.first().values.single()
        confirmation.data.shouldBeInstanceOf<PendingWorkspaceConfirmation.ConfirmationData.WorkspaceCloseConfirmation>()
    }

    @Test
    fun `a close confirmation resolved by the user offers no undo`() = runTest(UnconfinedTestDispatcher()) {
        val id = createTab(Workspace.Type.EDITOR)
        fake(id).info.value = fake(id).info.value.copy(hasUnsavedChanges = true)

        closeUndoable(id)
        val confirmationId = repo.pendingConfirmations.first().keys.single()
        repo.resolveConfirmation(confirmationId, confirmed = true)
        settle()

        openIds() shouldBe emptyList()
        stash.feedback.value shouldBe null
    }

    // ==================== Invalidation (H2) ====================

    @Test
    fun `pausing another tab does not drop the entry`() = runTest(UnconfinedTestDispatcher()) {
        val other = createTab()
        val closed = createTab()

        closeUndoable(closed)
        repo.execute(WorkspaceAction.Pause(other))
        settle()

        // Pause swaps an instance in place; the published id set is what an undo depends on
        stash.feedback.value shouldNotBe null
        undo().shouldBeInstanceOf<WorkspaceAction.UndoClose.Result.Success>()
    }

    @Test
    fun `resuming a tab does not drop the entry`() = runTest(UnconfinedTestDispatcher()) {
        val other = createTab()
        val closed = createTab()
        repo.execute(WorkspaceAction.Pause(other))
        settle()

        closeUndoable(closed)
        repo.execute(WorkspaceAction.Resume(other))
        settle()

        stash.feedback.value shouldNotBe null
    }

    @Test
    fun `creating a tab drops the entry`() = runTest(UnconfinedTestDispatcher()) {
        val closed = createTab()

        closeUndoable(closed)
        createTab()

        stash.feedback.value shouldBe null
        undo() shouldBe WorkspaceAction.UndoClose.Result.Unavailable
    }

    @Test
    fun `closing something else drops the entry`() = runTest(UnconfinedTestDispatcher()) {
        val other = createTab()
        val closed = createTab()

        closeUndoable(closed)
        repo.execute(WorkspaceAction.Close(other))
        settle()

        stash.feedback.value shouldBe null
    }

    @Test
    fun `a tab created while capturing drops the entry`() = runTest(UnconfinedTestDispatcher()) {
        val closed = createTab()
        fake(closed).whileCapturingArguments = {
            // The capture window runs without the repo lock: this is not the close's own mutation,
            // even though the close it lands in the middle of is the one that armed the stash.
            repo.execute(
                WorkspaceAction.Create(
                    type = Workspace.Type.EXPLORER,
                    arguments = FakeArguments(Workspace.Type.EXPLORER),
                )
            )
        }

        closeUndoable(closed)

        openIds().contains(closed) shouldBe false
        stash.feedback.value shouldBe null
        undo() shouldBe WorkspaceAction.UndoClose.Result.Unavailable
    }

    @Test
    fun `a close cancelled while it commits stops shielding a later entry`() =
        runTest(UnconfinedTestDispatcher()) {
            val abandoned = createTab()
            var abandonedToken: Long? = null
            fake(abandoned).whileCapturingArguments = { abandonedToken = stash.closeTokenFor(abandoned) }
            // Cancelled past its capture window, while the close was already tearing the tab down
            coEvery { operationsManager.removeWorkspace(abandoned) } throws CancellationException("cancelled")

            shouldThrow<CancellationException> {
                repo.execute(WorkspaceAction.Close(abandoned, undoable = true))
            }
            settle()
            val token = abandonedToken!!

            // A later close builds an entry of its own and is the one being offered
            closeUndoable(createTab())
            stash.feedback.value shouldNotBe null

            // The abandoned close has to give its token back on every way out, cancellation
            // included; otherwise a publication carrying it stays exempt forever and the entry that
            // superseded it survives changes it should not have.
            stash.onWorkspaceIdSetChanged(token)

            stash.feedback.value shouldBe null
        }

    @Test
    fun `a reorder keeps the entry and the tab returns beside its neighbours`() =
        runTest(UnconfinedTestDispatcher()) {
            val first = createTab()
            val closed = createTab()
            val last = createTab()

            closeUndoable(closed)
            repo.execute(WorkspaceAction.Reorder(listOf(last, first)))
            settle()

            stash.feedback.value shouldNotBe null
            undo()

            // Position is re-derived from the neighbour that came before it, wherever it moved to
            openIds() shouldBe listOf(last, first, closed)
        }

    @Test
    fun `the entry cannot outlive the free-tier limit`() = runTest(UnconfinedTestDispatcher()) {
        repeat(WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT) { createTab() }
        val closed = openIds().last()

        closeUndoable(closed)
        createTab()

        undo() shouldBe WorkspaceAction.UndoClose.Result.Unavailable
        repo.state.first().infos.size shouldBe WorkspaceRepo.FREE_TIER_WORKSPACE_LIMIT
    }

    @Test
    fun `the bar goes away when its window elapses`() = runTest(UnconfinedTestDispatcher()) {
        val id = createTab()

        closeUndoable(id)
        stash.feedback.value shouldNotBe null

        scope.testScheduler.advanceTimeBy(ClosedWorkspaceStash.FEEDBACK_TIMEOUT + 1.seconds)
        scope.testScheduler.runCurrent()

        stash.feedback.value shouldBe null
        undo() shouldBe WorkspaceAction.UndoClose.Result.Unavailable
    }

    // ==================== Restore preflight ====================

    @Test
    fun `a content duplicate that predates the close does not block the undo`() =
        runTest(UnconfinedTestDispatcher()) {
            val path: APath<*> = LocalPath.build("/storage/emulated/0/notes.txt")
            val holder = createTab(Workspace.Type.EDITOR)
            fake(holder).info.value = fake(holder).info.value.copy(contentPath = path)
            val closed = createTab(Workspace.Type.EDITOR)
            fake(closed).info.value = fake(closed).info.value.copy(contentPath = path)

            closeUndoable(closed)

            // The duplicate was already there when the tab closed, so it is not a new conflict
            undo().shouldBeInstanceOf<WorkspaceAction.UndoClose.Result.Success>()
            openIds() shouldBe listOf(holder, closed)
        }

    @Test
    fun `a tab opened on the closed tab's file drops the entry`() = runTest(UnconfinedTestDispatcher()) {
        val path: APath<*> = LocalPath.build("/storage/emulated/0/notes.txt")
        val closed = createTab(Workspace.Type.EDITOR)
        fake(closed).info.value = fake(closed).info.value.copy(contentPath = path)

        closeUndoable(closed)
        val newHolder = createTab(Workspace.Type.EDITOR)
        fake(newHolder).info.value = fake(newHolder).info.value.copy(contentPath = path)

        // The create already dropped the entry, so there is nothing to refuse
        undo() shouldBe WorkspaceAction.UndoClose.Result.Unavailable
        openIds() shouldBe listOf(newHolder)
    }

    @Test
    fun `a duplicate holder that has moved on does not block the undo`() =
        runTest(UnconfinedTestDispatcher()) {
            val path: APath<*> = LocalPath.build("/storage/emulated/0/notes.txt")
            val holder = createTab(Workspace.Type.EDITOR)
            fake(holder).info.value = fake(holder).info.value.copy(contentPath = path)
            val closed = createTab(Workspace.Type.EDITOR)
            fake(closed).info.value = fake(closed).info.value.copy(contentPath = path)

            closeUndoable(closed)
            // The tab that made the path a duplicate opened something else in the undo window
            fake(holder).info.value = fake(holder).info.value.copy(
                contentPath = LocalPath.build("/storage/emulated/0/other.txt"),
            )

            // Nothing holds the path any more, so the undo creates no conflict at all
            undo().shouldBeInstanceOf<WorkspaceAction.UndoClose.Result.Success>()
            openIds() shouldBe listOf(holder, closed)
        }

    @Test
    fun `a baseline holder replaced in place blocks the undo`() = runTest(UnconfinedTestDispatcher()) {
        val path: APath<*> = LocalPath.build("/storage/emulated/0/notes.txt")
        val holder = createTab(Workspace.Type.EDITOR)
        fake(holder).info.value = fake(holder).info.value.copy(contentPath = path)
        val closed = createTab(Workspace.Type.EDITOR)
        fake(closed).info.value = fake(closed).info.value.copy(contentPath = path)

        closeUndoable(closed)
        // A morph keeps the tab's id, so the id set never changes and the entry survives - but the
        // workspace on the path is a different one now, which is what the baseline is compared for.
        repo.execute(
            WorkspaceAction.Create(
                type = Workspace.Type.EDITOR,
                arguments = FakeArguments(Workspace.Type.EDITOR),
                replace = holder,
                id = holder,
            )
        )
        settle()
        fake(holder).info.value = fake(holder).info.value.copy(contentPath = path)

        undo() shouldBe WorkspaceAction.UndoClose.Result.Refused
        openIds() shouldBe listOf(holder)
    }

    @Test
    fun `a second undo does nothing`() = runTest(UnconfinedTestDispatcher()) {
        val id = createTab()

        closeUndoable(id)
        undo().shouldBeInstanceOf<WorkspaceAction.UndoClose.Result.Success>()
        undo() shouldBe WorkspaceAction.UndoClose.Result.Unavailable

        openIds() shouldBe listOf(id)
    }
}
