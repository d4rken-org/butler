package eu.darken.butler.workspace.core

import android.os.Parcel
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import eu.darken.butler.workspace.ui.WorkspacePageManager
import eu.darken.butler.workspace.ui.floatingbar.WorkspaceBarCollapseStates
import eu.darken.butler.workspace.ui.scroll.WorkspaceScrollPositions
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class WorkspaceAutoPauseManagerTest : BaseTest() {

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

    /** An overlay that owes its caller no result, i.e. one that may go down with its owner. */
    private class FakeChildArguments(
        override val type: Workspace.Type,
        override val callerWorkspaceId: Workspace.Id?,
        override val modalPresentation: Workspace.ModalPresentationMode =
            Workspace.ModalPresentationMode.PANE_LOCAL,
    ) : Workspace.ArgumentsWithCaller {
        override val pausableAsChild: Boolean = true
        override fun describeContents(): Int = 0
        override fun writeToParcel(dest: Parcel, flags: Int) = Unit
    }

    private class FakeWorkspace(
        override val id: Workspace.Id,
        private val arguments: Workspace.Arguments,
    ) : Workspace<Workspace.Arguments> {
        override val type: Workspace.Type = arguments.type

        /** Runs inside [createArguments], for exercising state changes while it suspends. */
        var whileCapturingArguments: (suspend () -> Unit)? = null

        override val info = MutableStateFlow(
            Workspace.Info(
                id = id,
                type = type,
                title = "Fake $type".toCaString(),
                lifecycleState = Workspace.LifecycleState.Ready,
                callerWorkspaceId = (arguments as? Workspace.ArgumentsWithCaller)?.callerWorkspaceId,
                modalPresentation = (arguments as? Workspace.ArgumentsWithCaller)?.modalPresentation
                    ?: Workspace.ModalPresentationMode.PANE_LOCAL,
                pausableAsChild = arguments.isPausableAsChild,
            )
        )

        override suspend fun createArguments(): Workspace.Arguments {
            whileCapturingArguments?.invoke()
            return arguments
        }
    }

    private val createdWorkspaces = mutableListOf<FakeWorkspace>()

    private inner class FakeFactory : WorkspaceFactory<Workspace.Arguments> {
        override fun create(id: Workspace.Id, arguments: Workspace.Arguments): Workspace<Workspace.Arguments> =
            FakeWorkspace(id, arguments).also { createdWorkspaces += it }

        override val argumentsSerializer: KSerializer<Workspace.Arguments>
            get() = throw NotImplementedError("serialize/deserialize are not used here")

        override fun serialize(json: Json, arguments: Workspace.Arguments): JsonElement = JsonNull

        override fun deserialize(json: Json, element: JsonElement): Workspace.Arguments =
            FakeArguments(Workspace.Type.EXPLORER)
    }

    private val enabledState = MutableStateFlow(true)
    private val timeoutState = MutableStateFlow(2.hours)

    /** Set to make the next evaluation blow up while reading settings. */
    private var failNextEvaluation = false

    private var now: Instant = Instant.fromEpochSeconds(1_700_000_000)
    private val clock = object : Clock {
        override fun now(): Instant = now
    }

    private lateinit var scope: TestScope
    private lateinit var repo: WorkspaceRepo
    private lateinit var pageManager: WorkspacePageManager
    private lateinit var pauseGate: WorkspacePauseGate

    @BeforeEach
    fun setup() {
        createdWorkspaces.clear()
        pauseGate = WorkspacePauseGate()
        enabledState.value = true
        timeoutState.value = 2.hours
        failNextEvaluation = false
        now = Instant.fromEpochSeconds(1_700_000_000)

        scope = TestScope(UnconfinedTestDispatcher())

        val upgradeInfo = mockk<UpgradeRepo.Info>().apply {
            every { isPro } returns true
            every { isSettled } returns true
            every { error } returns null
        }
        // Mirrors the real repo: a hot flow that never completes (see WorkspaceRepoTest).
        val upgradeRepo = mockk<UpgradeRepo>().apply {
            every { this@apply.upgradeInfo } returns MutableStateFlow(upgradeInfo)
            coEvery { refresh() } just Runs
        }
        repo = WorkspaceRepo(
            appScope = scope,
            factoryMap = Workspace.Type.entries.associateWith { FakeFactory() },
            workspaceSettings = workspaceSettings,
            operationsManager = mockk(relaxed = true),
            upgradeRepo = upgradeRepo,
            usageRepo = mockk(relaxed = true),
        )
        pageManager = WorkspacePageManager(
            appScope = scope,
            workspaceRemote = repo,
            scrollPositions = WorkspaceScrollPositions(),
            barCollapseStates = WorkspaceBarCollapseStates(),
        )
    }

    private val workspaceSettings: WorkspaceSettings = mockk<WorkspaceSettings>(relaxed = true).apply {
        every { layoutModePortrait.flow } returns flowOf(WorkspacePanelMode.AUTO)
        every { layoutModeLandscape.flow } returns flowOf(WorkspacePanelMode.AUTO)
        every { autoPauseEnabled.flow } returns flow {
            if (failNextEvaluation) {
                failNextEvaluation = false
                throw IllegalStateException("Settings exploded")
            }
            emitAll(enabledState)
        }
        every { autoPauseIdleTimeout.flow } returns timeoutState
    }

    private fun createManager() = WorkspaceAutoPauseManager(
        appScope = scope,
        workspaceSettings = workspaceSettings,
        workspaceRepo = repo,
        workspacePageManager = pageManager,
        workspacePauseGate = pauseGate,
        clock = clock,
    )

    private fun WorkspaceAutoPauseManager.evaluateNow() {
        onAppForegrounded()
        scope.testScheduler.runCurrent()
    }

    private suspend fun createTab(type: Workspace.Type = Workspace.Type.EXPLORER): Workspace.Id {
        val result = repo.execute(WorkspaceAction.Create(type = type, arguments = FakeArguments(type)))
        scope.testScheduler.runCurrent()
        return (result as WorkspaceAction.Create.Result.Success).newId
    }

    private suspend fun createPicker(caller: Workspace.Id): Workspace.Id {
        val type = Workspace.Type.EXPLORER
        val result = repo.execute(
            WorkspaceAction.Create(type = type, arguments = FakePickerArguments(type, caller))
        )
        scope.testScheduler.runCurrent()
        return (result as WorkspaceAction.Create.Result.Success).newId
    }

    private suspend fun createChild(caller: Workspace.Id): Workspace.Id {
        val type = Workspace.Type.APP_DETAILS
        val result = repo.execute(
            WorkspaceAction.Create(type = type, arguments = FakeChildArguments(type, caller))
        )
        scope.testScheduler.runCurrent()
        return (result as WorkspaceAction.Create.Result.Success).newId
    }

    private suspend fun isPaused(id: Workspace.Id): Boolean =
        repo.state.first().infos.single { it.id == id }.isPaused

    private fun fake(id: Workspace.Id): FakeWorkspace = createdWorkspaces.last { it.id == id }

    private fun elapse(duration: Duration) {
        now += duration
    }

    @Test
    fun `a hidden workspace is paused once the threshold passes`() = runTest(UnconfinedTestDispatcher()) {
        val visibleId = createTab()
        val hiddenId = createTab()
        val manager = createManager()

        manager.evaluateNow()
        isPaused(hiddenId) shouldBe false

        elapse(2.hours + 1.minutes)
        manager.evaluateNow()

        isPaused(hiddenId) shouldBe true
        isPaused(visibleId) shouldBe false
    }

    @Test
    fun `a hidden workspace is not paused before the threshold`() = runTest(UnconfinedTestDispatcher()) {
        createTab()
        val hiddenId = createTab()
        val manager = createManager()

        manager.evaluateNow()
        elapse(1.hours + 59.minutes)
        manager.evaluateNow()

        isPaused(hiddenId) shouldBe false
    }

    @Test
    fun `a workspace selected in a live pane is never paused`() = runTest(UnconfinedTestDispatcher()) {
        pageManager.setPaneCount(2)
        val focusedId = createTab()
        val secondPaneId = createTab()
        pageManager.state.value.selectedWorkspaces.values.toSet() shouldBe setOf(focusedId, secondPaneId)
        val manager = createManager()

        manager.evaluateNow()
        elapse(5.hours)
        manager.evaluateNow()

        isPaused(focusedId) shouldBe false
        isPaused(secondPaneId) shouldBe false
    }

    @Test
    fun `panes collapsing leaves stale selections behind, which still get paused`() =
        runTest(UnconfinedTestDispatcher()) {
            val firstId = createTab()
            val secondId = createTab()
            val thirdId = createTab()
            val fourthId = createTab()
            pageManager.setPaneCount(4)
            pageManager.state.value.selectedWorkspaces.size shouldBe 4

            pageManager.setPaneCount(1)
            // setPaneCount() does not prune out-of-range entries; they must not count as visible
            pageManager.state.value.selectedWorkspaces.size shouldBe 4

            val manager = createManager()
            manager.evaluateNow()
            elapse(3.hours)
            manager.evaluateNow()

            isPaused(firstId) shouldBe false
            isPaused(secondId) shouldBe true
            isPaused(thirdId) shouldBe true
            isPaused(fourthId) shouldBe true
        }

    @Test
    fun `the idle clock starts when a workspace leaves the screen`() = runTest(UnconfinedTestDispatcher()) {
        val firstId = createTab()
        val secondId = createTab()
        val manager = createManager()

        manager.evaluateNow()

        // firstId only becomes hidden an hour in, so its clock must start there
        elapse(1.hours)
        pageManager.setLayout(mapOf(0 to secondId), focusedId = secondId)
        manager.evaluateNow()

        elapse(1.hours + 30.minutes)
        manager.evaluateNow()
        isPaused(firstId) shouldBe false

        elapse(35.minutes)
        manager.evaluateNow()
        isPaused(firstId) shouldBe true
    }

    @Test
    fun `a busy workspace is not paused`() = runTest(UnconfinedTestDispatcher()) {
        createTab()
        val hiddenId = createTab()
        fake(hiddenId).info.update { it.copy(operationCount = 1) }
        val manager = createManager()

        manager.evaluateNow()
        elapse(3.hours)
        manager.evaluateNow()

        isPaused(hiddenId) shouldBe false
    }

    @Test
    fun `a workspace needing attention is not paused`() = runTest(UnconfinedTestDispatcher()) {
        createTab()
        val hiddenId = createTab()
        fake(hiddenId).info.update { it.copy(attentionCount = 1) }
        val manager = createManager()

        manager.evaluateNow()
        elapse(3.hours)
        manager.evaluateNow()

        isPaused(hiddenId) shouldBe false
    }

    @Test
    fun `a workspace with unsaved changes is not paused`() = runTest(UnconfinedTestDispatcher()) {
        createTab()
        val hiddenId = createTab()
        fake(hiddenId).info.update { it.copy(hasUnsavedChanges = true) }
        val manager = createManager()

        manager.evaluateNow()
        elapse(3.hours)
        manager.evaluateNow()

        isPaused(hiddenId) shouldBe false
    }

    @Test
    fun `a workspace that opted out of pausing is not paused`() = runTest(UnconfinedTestDispatcher()) {
        createTab()
        val hiddenId = createTab()
        fake(hiddenId).info.update { it.copy(isPausable = false) }
        val manager = createManager()

        manager.evaluateNow()
        elapse(3.hours)
        manager.evaluateNow()

        isPaused(hiddenId) shouldBe false
    }

    @Test
    fun `a picker and its parent are not paused`() = runTest(UnconfinedTestDispatcher()) {
        val parentId = createTab()
        val otherId = createTab()
        val childId = createPicker(caller = parentId)
        val manager = createManager()

        manager.evaluateNow()
        elapse(3.hours)
        manager.evaluateNow()

        // The picker owes its caller a result, so neither of them may be released
        isPaused(parentId) shouldBe false
        isPaused(childId) shouldBe false
        isPaused(otherId) shouldBe true
    }

    /**
     * A pane-local overlay of an unselected tab, on a layout with panes: the only way a stack can be
     * off screen while it exists. Single-pane promotes every chain to the full-screen dialog, which
     * renders even when focus points elsewhere - see the fallback test below.
     */
    private suspend fun createHiddenStack(): Pair<Workspace.Id, Workspace.Id> {
        pageManager.setPaneCount(2)
        createTab()
        createTab()
        // Both panes are taken, so this one is created without a pane of its own
        val hiddenId = createTab()
        pageManager.state.value.selectedWorkspaces.values.contains(hiddenId) shouldBe false
        return hiddenId to createChild(caller = hiddenId)
    }

    @Test
    fun `an idle tab is paused together with its overlay`() = runTest(UnconfinedTestDispatcher()) {
        val (hiddenId, overlayId) = createHiddenStack()
        val manager = createManager()

        manager.evaluateNow()
        elapse(3.hours)
        manager.evaluateNow()

        isPaused(hiddenId) shouldBe true
        isPaused(overlayId) shouldBe true
        pageManager.state.value.selectedWorkspaces.values.forEach { isPaused(it) shouldBe false }
    }

    @Test
    fun `an overlay that keeps its own state keeps its whole stack alive`() =
        runTest(UnconfinedTestDispatcher()) {
            val (hiddenId, overlayId) = createHiddenStack()
            // The Saver case: a transient export flow lives only in the instance
            fake(overlayId).info.update { it.copy(isPausable = false) }
            val manager = createManager()

            manager.evaluateNow()
            elapse(3.hours)
            manager.evaluateNow()

            isPaused(hiddenId) shouldBe false
            isPaused(overlayId) shouldBe false
        }

    @Test
    fun `a rendered modal stack is never paused, even when an unrelated tab holds focus`() =
        runTest(UnconfinedTestDispatcher()) {
            val focusedId = createTab()
            val modalOwnerId = createTab()
            val idleId = createTab()
            val overlayId = createChild(caller = modalOwnerId)
            // Single pane: the overlay renders as a full-screen dialog. Focus points at a tab with no
            // chain at all, so the renderer falls back to the newest chain - which is this one.
            pageManager.setLayout(mapOf(0 to focusedId), focusedId = focusedId)
            val manager = createManager()

            manager.evaluateNow()
            elapse(3.hours)
            manager.evaluateNow()

            isPaused(modalOwnerId) shouldBe false
            isPaused(overlayId) shouldBe false
            // The pass did run; only the on-screen stack was spared
            isPaused(idleId) shouldBe true
        }

    @Test
    fun `closing an overlay restarts its tab's idle clock instead of pausing it right away`() =
        runTest(UnconfinedTestDispatcher()) {
            val focusedId = createTab()
            val modalOwnerId = createTab()
            val overlayId = createChild(caller = modalOwnerId)
            pageManager.setLayout(mapOf(0 to focusedId), focusedId = focusedId)
            val manager = createManager()

            manager.evaluateNow()
            elapse(3.hours)
            manager.evaluateNow()
            isPaused(modalOwnerId) shouldBe false

            repo.execute(WorkspaceAction.Close(overlayId))
            scope.testScheduler.runCurrent()

            // The tab was on screen the whole time the overlay was up, so no stale idle stamp may
            // survive it - otherwise it pauses on the very first pass after the overlay closes
            manager.evaluateNow()
            isPaused(modalOwnerId) shouldBe false

            elapse(2.hours + 1.minutes)
            manager.evaluateNow()
            isPaused(modalOwnerId) shouldBe true
        }

    @Test
    fun `a child created while a pause waits for the lease is not left behind`() =
        runTest(UnconfinedTestDispatcher()) {
            val visibleId = createTab()
            val hiddenId = createTab()
            pageManager.setLayout(mapOf(0 to visibleId), focusedId = visibleId)
            val manager = createManager()
            manager.evaluateNow()
            elapse(3.hours)

            // Stands in for a preview capture holding the unit's lease
            val captureDone = CompletableDeferred<Unit>()
            val capture = launch { pauseGate.withLease(hiddenId) { captureDone.await() } }

            manager.evaluateNow()
            isPaused(hiddenId) shouldBe false

            // The topology the pause acts on is resolved inside the repo, not before the lease
            val pickerId = createPicker(caller = hiddenId)
            captureDone.complete(Unit)
            capture.join()
            scope.testScheduler.runCurrent()

            // Refused as a unit: never a released tab with a live picker on top of it
            isPaused(hiddenId) shouldBe false
            isPaused(pickerId) shouldBe false
        }

    @Test
    fun `nothing is paused while the tab manager overlay is visible`() = runTest(UnconfinedTestDispatcher()) {
        createTab()
        val hiddenId = createTab()
        val manager = createManager()

        manager.evaluateNow()
        pageManager.showManagerOverlay()
        elapse(3.hours)
        manager.evaluateNow()
        isPaused(hiddenId) shouldBe false

        pageManager.hideManagerOverlay()
        manager.evaluateNow()
        isPaused(hiddenId) shouldBe true
    }

    @Test
    fun `nothing is paused while auto-pause is disabled`() = runTest(UnconfinedTestDispatcher()) {
        enabledState.value = false
        createTab()
        val hiddenId = createTab()
        val manager = createManager()

        manager.evaluateNow()
        elapse(5.hours)
        manager.evaluateNow()

        isPaused(hiddenId) shouldBe false
    }

    @Test
    fun `a shortened threshold is respected`() = runTest(UnconfinedTestDispatcher()) {
        timeoutState.value = 15.minutes
        createTab()
        val hiddenId = createTab()
        val manager = createManager()

        manager.evaluateNow()
        elapse(20.minutes)
        manager.evaluateNow()

        isPaused(hiddenId) shouldBe true
    }

    @Test
    fun `a failing evaluation does not kill the loop`() = runTest(UnconfinedTestDispatcher()) {
        createTab()
        val hiddenId = createTab()
        val manager = createManager()

        manager.evaluateNow()
        elapse(3.hours)

        failNextEvaluation = true
        manager.evaluateNow()
        isPaused(hiddenId) shouldBe false

        manager.evaluateNow()
        isPaused(hiddenId) shouldBe true
    }

    @Test
    fun `a workspace that gains focus while being paused is resumed right away`() =
        runTest(UnconfinedTestDispatcher()) {
            val visibleId = createTab()
            val hiddenId = createTab()
            val manager = createManager()
            manager.evaluateNow()

            fake(hiddenId).whileCapturingArguments = {
                pageManager.setLayout(mapOf(0 to hiddenId), focusedId = hiddenId)
            }

            elapse(3.hours)
            manager.evaluateNow()

            isPaused(hiddenId) shouldBe false
            repo.retrieve(hiddenId).first() shouldNotBe null
            createdWorkspaces.count { it.id == hiddenId } shouldBe 2
            isPaused(visibleId) shouldBe false
        }

    @Test
    fun `a stack the renderer promotes to full-screen while being paused is resumed right away`() =
        runTest(UnconfinedTestDispatcher()) {
            val (hiddenId, overlayId) = createHiddenStack()
            val manager = createManager()
            manager.evaluateNow()

            // A rotation collapsing the panes: this unselected tab's pane-local overlay becomes the
            // full-screen dialog, so the user is looking at the very stack being released
            fake(hiddenId).whileCapturingArguments = { pageManager.setPaneCount(1) }

            elapse(3.hours)
            manager.evaluateNow()

            // Nothing moved selection or focus onto the stack; only the fallback renders it
            val pageState = pageManager.state.value
            pageState.selectedWorkspaces
                .filterKeys { it in 0 until pageState.currentPaneCount }
                .values.contains(hiddenId) shouldBe false
            pageState.focusedWorkspaceId shouldNotBe hiddenId
            pageState.focusedWorkspaceId shouldNotBe overlayId

            isPaused(hiddenId) shouldBe false
            isPaused(overlayId) shouldBe false
            // It really was paused and undone, not merely spared
            createdWorkspaces.count { it.id == hiddenId } shouldBe 2
            createdWorkspaces.count { it.id == overlayId } shouldBe 2
        }

    @Test
    fun `the tab manager opening mid-pass spares the remaining candidates`() =
        runTest(UnconfinedTestDispatcher()) {
            createTab()
            val firstHiddenId = createTab()
            val secondHiddenId = createTab()
            val thirdHiddenId = createTab()
            val manager = createManager()
            manager.evaluateNow()

            // The overlay drives offscreen preview capture, so releasing instances while it is up
            // would pull one out from under a composing preview
            fake(firstHiddenId).whileCapturingArguments = { pageManager.showManagerOverlay() }

            elapse(3.hours)
            manager.evaluateNow()

            // The one already in flight is undone by the backstop, the rest are never touched
            isPaused(firstHiddenId) shouldBe false
            createdWorkspaces.count { it.id == firstHiddenId } shouldBe 2
            isPaused(secondHiddenId) shouldBe false
            isPaused(thirdHiddenId) shouldBe false
            createdWorkspaces.count { it.id == secondHiddenId } shouldBe 1
            createdWorkspaces.count { it.id == thirdHiddenId } shouldBe 1
        }

    @Test
    fun `a preview capture of the same workspace waits for the pause to finish`() =
        runTest(UnconfinedTestDispatcher()) {
            createTab()
            val hiddenId = createTab()
            val manager = createManager()
            manager.evaluateNow()

            val order = mutableListOf<String>()
            val outer = this
            // Stands in for WorkspacePreviewCaptureService, which takes the same lease
            fake(hiddenId).whileCapturingArguments = {
                order += "pause-in-flight"
                outer.launch(start = CoroutineStart.UNDISPATCHED) {
                    pauseGate.withLease(hiddenId) { order += "capture" }
                }
                order += "pause-still-in-flight"
            }

            elapse(3.hours)
            manager.evaluateNow()
            // Lets the capture proceed once the pause released the lease
            testScheduler.runCurrent()

            isPaused(hiddenId) shouldBe true
            order shouldBe listOf("pause-in-flight", "pause-still-in-flight", "capture")
        }

    @Test
    fun `a preview capture of another workspace is not blocked by a pause`() =
        runTest(UnconfinedTestDispatcher()) {
            val visibleId = createTab()
            val hiddenId = createTab()
            val manager = createManager()
            manager.evaluateNow()

            val order = mutableListOf<String>()
            val outer = this
            fake(hiddenId).whileCapturingArguments = {
                order += "pause-in-flight"
                outer.launch(start = CoroutineStart.UNDISPATCHED) {
                    pauseGate.withLease(visibleId) { order += "capture" }
                }
                order += "pause-still-in-flight"
            }

            elapse(3.hours)
            manager.evaluateNow()

            order shouldBe listOf("pause-in-flight", "capture", "pause-still-in-flight")
        }

    @Test
    fun `idle bookkeeping alone does not touch the page manager state`() = runTest(UnconfinedTestDispatcher()) {
        createTab()
        createTab()
        val manager = createManager()

        manager.evaluateNow()
        val before = pageManager.state.value

        elapse(30.minutes)
        manager.evaluateNow()
        elapse(30.minutes)
        manager.evaluateNow()

        // A page-manager write would make WorkspaceSessionManager save the whole session
        pageManager.state.value shouldBe before
    }
}
