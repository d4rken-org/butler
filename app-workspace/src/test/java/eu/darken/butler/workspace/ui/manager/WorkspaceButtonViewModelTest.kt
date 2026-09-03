package eu.darken.butler.workspace.ui.manager

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.defaultArguments
import eu.darken.butler.workspace.core.usage.WorkspaceUsageRepo
import eu.darken.butler.workspace.ui.WorkspacePageManager
import eu.darken.butler.workspace.ui.template.QuickCreateItem
import eu.darken.butler.workspace.ui.template.WorkspaceTemplate
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

class WorkspaceButtonViewModelTest : BaseTest() {

    private val workspaceRemote = mockk<WorkspaceRemote>(relaxed = true).apply {
        every { state } returns flowOf(WorkspaceRemote.State())
    }
    private val pageManagerState = MutableStateFlow(WorkspacePageManager.State())
    private val pageManager = mockk<WorkspacePageManager>(relaxed = true).apply {
        every { state } returns pageManagerState
    }

    private fun focusOn(id: Workspace.Id) {
        pageManagerState.value = WorkspacePageManager.State(focusedWorkspaceId = id)
    }

    private class FakeTemplate(
        override val type: Workspace.Type,
        override val sortOrder: Int,
        override val isQuickCreate: Boolean = false,
        override val availability: Flow<Boolean> = flowOf(true),
    ) : WorkspaceTemplate {
        override val icon: ImageVector = Icons.TwoTone.Add
        override val title: CaString = type.name.toCaString()
        override val subtitle: CaString = type.name.toCaString()
        override val arguments: Workspace.Arguments = type.defaultArguments!!
    }

    private fun createVM(
        templates: Set<WorkspaceTemplate> = emptySet(),
        ranked: Flow<List<Workspace.Type>> = flowOf(emptyList()),
        dispatcher: CoroutineDispatcher? = null,
    ) = WorkspaceButtonViewModel(
        dispatchers = TestDispatcherProvider(dispatcher),
        workspaceRemote = workspaceRemote,
        workspacePageManager = pageManager,
        workspaceTemplates = templates,
        usageRepo = mockk<WorkspaceUsageRepo>().apply {
            every { rankedTypes } returns ranked
        },
    )

    private fun explorerItem() = QuickCreateItem(
        type = Workspace.Type.EXPLORER,
        icon = Icons.TwoTone.Add,
        title = "Explorer".toCaString(),
        arguments = Workspace.Type.EXPLORER.defaultArguments!!,
    )

    @Test
    fun `createWorkspace executes Create with the item type and args and requests selection`() {
        val newId = Workspace.Id()
        val focused = Workspace.Id()
        focusOn(focused)
        val action = slot<WorkspaceAction>()
        coEvery { workspaceRemote.execute(capture(action)) } returns WorkspaceAction.Create.Result.Success(newId)
        val item = explorerItem()

        createVM().createWorkspace(item)

        val created = action.captured as WorkspaceAction.Create
        created.type shouldBe Workspace.Type.EXPLORER
        created.arguments shouldBe item.arguments
        // The focused tab is the origin: the new tab is placed right of it
        created.sourceWorkspaceId shouldBe focused
        coVerify { workspaceRemote.emitEvent(WorkspaceEvent.SelectionRequested(newId, focused)) }
    }

    @Test
    fun `createWorkspace on AlreadyOpen requests selection of the existing workspace`() {
        val existingId = Workspace.Id()
        val focused = Workspace.Id()
        focusOn(focused)
        coEvery { workspaceRemote.execute(any()) } returns WorkspaceAction.Create.Result.AlreadyOpen(existingId)

        createVM().createWorkspace(explorerItem())

        coVerify { workspaceRemote.emitEvent(WorkspaceEvent.SelectionRequested(existingId, focused)) }
    }

    /**
     * Focus is read when the button is tapped, not when the queued create runs - anything else
     * anchors the tab to wherever the user navigated in between.
     */
    @Test
    fun `the origin is the tab focused at tap time, not when the create runs`() = runTest {
        val focusedAtTap = Workspace.Id()
        focusOn(focusedAtTap)
        val action = slot<WorkspaceAction>()
        coEvery { workspaceRemote.execute(capture(action)) } returns
            WorkspaceAction.Create.Result.Success(Workspace.Id())
        val vm = createVM(dispatcher = StandardTestDispatcher(testScheduler))

        vm.createWorkspace(explorerItem())
        focusOn(Workspace.Id())
        runCurrent()

        (action.captured as WorkspaceAction.Create).sourceWorkspaceId shouldBe focusedAtTap
    }

    @Test
    fun `createWorkspace on LimitReached emits no selection event`() {
        coEvery { workspaceRemote.execute(any()) } returns WorkspaceAction.Create.Result.LimitReached

        createVM().createWorkspace(explorerItem())

        coVerify(exactly = 0) { workspaceRemote.emitEvent(any()) }
    }

    @Test
    fun `createTemplatesWorkspace executes Create for the templates picker`() {
        val newId = Workspace.Id()
        val focused = Workspace.Id()
        focusOn(focused)
        val action = slot<WorkspaceAction>()
        coEvery { workspaceRemote.execute(capture(action)) } returns WorkspaceAction.Create.Result.Success(newId)

        createVM().createTemplatesWorkspace()

        val created = action.captured as WorkspaceAction.Create
        created.type shouldBe Workspace.Type.TEMPLATES
        created.sourceWorkspaceId shouldBe focused
        coVerify { workspaceRemote.emitEvent(WorkspaceEvent.SelectionRequested(newId, focused)) }
    }

    private val allTemplates = setOf(
        FakeTemplate(Workspace.Type.EXPLORER, sortOrder = 10, isQuickCreate = true),
        FakeTemplate(Workspace.Type.SEARCHER, sortOrder = 20, isQuickCreate = true),
        FakeTemplate(Workspace.Type.EDITOR, sortOrder = 30, isQuickCreate = true),
        FakeTemplate(Workspace.Type.APPS, sortOrder = 40, isQuickCreate = true),
        FakeTemplate(Workspace.Type.HISTORY, sortOrder = 50),
    )

    @Test
    fun `recent items fall back to quick-create templates when nothing was used`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = createVM(templates = allTemplates)
            val states = mutableListOf<WorkspaceButtonViewModel.State?>()
            vm.state.onEach { states += it }.launchIn(backgroundScope)

            states.filterNotNull().last().recentItems.map { it.type } shouldBe listOf(
                Workspace.Type.EXPLORER,
                Workspace.Type.SEARCHER,
                Workspace.Type.EDITOR,
            )
        }

    @Test
    fun `usage ranking outranks the template sort order`() = runTest(UnconfinedTestDispatcher()) {
        val vm = createVM(
            templates = allTemplates,
            ranked = flowOf(listOf(Workspace.Type.HISTORY, Workspace.Type.EDITOR)),
        )
        val states = mutableListOf<WorkspaceButtonViewModel.State?>()
        vm.state.onEach { states += it }.launchIn(backgroundScope)

        states.filterNotNull().last().recentItems.map { it.type } shouldBe listOf(
            Workspace.Type.HISTORY,
            Workspace.Type.EDITOR,
            Workspace.Type.EXPLORER,
        )
    }

    @Test
    fun `unavailable templates never surface as recent items`() = runTest(UnconfinedTestDispatcher()) {
        val vm = createVM(
            templates = setOf(
                FakeTemplate(Workspace.Type.EXPLORER, sortOrder = 10, isQuickCreate = true),
                FakeTemplate(
                    Workspace.Type.DEVELOPER,
                    sortOrder = 5,
                    isQuickCreate = true,
                    availability = flowOf(false),
                ),
            ),
            ranked = flowOf(listOf(Workspace.Type.DEVELOPER, Workspace.Type.EXPLORER)),
        )
        val states = mutableListOf<WorkspaceButtonViewModel.State?>()
        vm.state.onEach { states += it }.launchIn(backgroundScope)

        states.filterNotNull().last().recentItems.map { it.type } shouldBe listOf(Workspace.Type.EXPLORER)
    }

    private fun info(id: Workspace.Id, caller: Workspace.Id? = null) = Workspace.Info(
        id = id,
        type = Workspace.Type.EXPLORER,
        title = "Explorer".toCaString(),
        callerWorkspaceId = caller,
    )

    private fun TestScope.unitsFor(
        infos: List<Workspace.Info>,
    ): Map<Workspace.Id, WorkspaceButtonViewModel.StackUnit> {
        every { workspaceRemote.state } returns flowOf(WorkspaceRemote.State(infos = infos))
        val states = mutableListOf<WorkspaceButtonViewModel.State?>()
        createVM().state.onEach { states += it }.launchIn(backgroundScope)
        return states.filterNotNull().last().unitsByMember
    }

    @Test
    fun `every member of a stack resolves to the owning tab and the full unit size`() =
        runTest(UnconfinedTestDispatcher()) {
            val root = Workspace.Id()
            val child = Workspace.Id()
            val grandchild = Workspace.Id()

            val units = unitsFor(
                listOf(info(root), info(child, caller = root), info(grandchild, caller = child)),
            )

            val expected = WorkspaceButtonViewModel.StackUnit(ownerId = root, size = 3)
            units[root] shouldBe expected
            units[child] shouldBe expected
            units[grandchild] shouldBe expected
        }

    @Test
    fun `sibling tabs stay separate units`() = runTest(UnconfinedTestDispatcher()) {
        val first = Workspace.Id()
        val second = Workspace.Id()

        val units = unitsFor(listOf(info(first), info(second)))

        units[first] shouldBe WorkspaceButtonViewModel.StackUnit(ownerId = first, size = 1)
        units[second] shouldBe WorkspaceButtonViewModel.StackUnit(ownerId = second, size = 1)
    }

    @Test
    fun `a workspace whose owner cannot be resolved falls back to closing itself`() =
        runTest(UnconfinedTestDispatcher()) {
            val orphan = Workspace.Id()
            val child = Workspace.Id()

            // The orphan names a caller that no longer exists, so its recovery unit is keyed on
            // whichever member comes first - which is not necessarily one the others hang off.
            // Promising a unit close here could leave the sibling open.
            val units = unitsFor(
                listOf(info(child, caller = orphan), info(orphan, caller = Workspace.Id())),
            )

            units[orphan] shouldBe WorkspaceButtonViewModel.StackUnit(ownerId = orphan, size = 1)
            units[child] shouldBe WorkspaceButtonViewModel.StackUnit(ownerId = child, size = 1)
        }

    @Test
    fun `state is emitted before template availability resolves`() = runTest(UnconfinedTestDispatcher()) {
        val availability = MutableSharedFlow<Boolean>()
        val ranked = MutableSharedFlow<List<Workspace.Type>>()
        val vm = createVM(
            templates = setOf(
                FakeTemplate(
                    Workspace.Type.EXPLORER,
                    sortOrder = 10,
                    isQuickCreate = true,
                    availability = availability,
                ),
            ),
            ranked = ranked,
        )
        val states = mutableListOf<WorkspaceButtonViewModel.State?>()
        vm.state.onEach { states += it }.launchIn(backgroundScope)

        // Badges must render even while the availability flow has not answered yet
        states.filterNotNull().last().recentItems shouldBe emptyList()

        availability.emit(true)
        ranked.emit(listOf(Workspace.Type.EXPLORER))

        states.filterNotNull().last().recentItems.map { it.type } shouldBe listOf(Workspace.Type.EXPLORER)
    }
}
