package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.serialization.SerializationIOModule
import eu.darken.butler.explorer.core.ExplorerSettings
import eu.darken.butler.explorer.core.ExplorerTabViewStore
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.FileTypeFilter
import eu.darken.butler.explorer.core.FilterState
import eu.darken.butler.explorer.core.SortSettings
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.sorting.rules.ExplorerTabSortStore
import eu.darken.butler.explorer.core.sorting.rules.FolderSortRulesRepo
import eu.darken.butler.explorer.core.sorting.rules.SortRuleLayer
import eu.darken.butler.explorer.core.sorting.rules.sortPathKey
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.restore.WorkspaceViewPrefs
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.mockDataStoreValue
import java.io.File

class ExplorerViewSettingsControllerTest : BaseTest() {

    private val json = SerializationIOModule().json()
    private val initialStyle = ExplorerViewStyle.default()
    private val initialSort = SortSettings()
    private val bySize = SortSettings(mode = SortSettings.Mode.SIZE, reversed = false)
    private val byModified = SortSettings(mode = SortSettings.Mode.MODIFIED_AT, reversed = true)

    private val workspaceId = Workspace.Id()
    private val dirA = LocalPath.build("/sdcard/A")
    private val dirB = LocalPath.build("/sdcard/B")

    /** Stateful, because a new tab has to open with the style the previous one last wrote. */
    private val globalStyle = MutableStateFlow<ExplorerViewStyle>(initialStyle)
    private val styleStore = mockk<DataStoreValue<ExplorerViewStyle>>().apply {
        every { flow } returns globalStyle
        coEvery { update(any()) } answers {
            val old = globalStyle.value
            val new = firstArg<(ExplorerViewStyle) -> ExplorerViewStyle?>().invoke(old) ?: old
            globalStyle.value = new
            DataStoreValue.Updated(old, new)
        }
    }
    private val sortStore = mockDataStoreValue(initialSort).apply {
        coEvery { update(any()) } returns DataStoreValue.Updated(initialSort, initialSort)
    }

    private val viewPrefs = WorkspaceViewPrefs()
    private val tabSortStore = ExplorerTabSortStore(viewPrefs, json)
    private val tabViewStore = ExplorerTabViewStore(viewPrefs, json)

    private fun mockSettings(): ExplorerSettings = mockk<ExplorerSettings>().apply {
        every { defaultViewStyle } returns styleStore
        every { sortSettings } returns sortStore
    }

    private fun mockRules(
        rulesFor: (LocalPath) -> List<FolderSortRulesRepo.ResolvedRule> = { emptyList() },
        block: FolderSortRulesRepo.() -> Unit = {},
    ): FolderSortRulesRepo = mockk<FolderSortRulesRepo>().apply {
        every { observeRulesFor(any()) } answers { flowOf(rulesFor(firstArg())) }
        block()
    }

    private fun rule(
        path: LocalPath,
        settings: SortSettings?,
        subtree: Boolean = false,
    ) = FolderSortRulesRepo.ResolvedRule(
        pathKey = path.sortPathKey(),
        path = path,
        settings = settings,
        subtree = subtree,
    )

    /**
     * Both state flows outlive the test body, so they belong to the background scope; `resolvedSort`
     * shares lazily, so it also needs an actual collector before it resolves anything.
     */
    private fun TestScope.controller(
        settings: ExplorerSettings = mockSettings(),
        rules: FolderSortRulesRepo = mockRules(),
        location: Flow<ExplorerLocation?> = flowOf(null),
        tabId: Workspace.Id = workspaceId,
    ) = ExplorerViewSettingsController(
        explorerSettings = settings,
        folderSortRules = rules,
        tabSortStore = tabSortStore,
        tabViewStore = tabViewStore,
        json = json,
        workspaceId = tabId,
        currentLocation = location,
        scope = backgroundScope,
        doLaunch = { block -> backgroundScope.launch { block() } },
    ).also { controller ->
        backgroundScope.launch { controller.resolvedSort.collect { } }
    }

    private fun directory(path: LocalPath) = mockk<ExplorerLocation.Directory>().apply {
        every { this@apply.path } returns path
        every { locationId } returns "location://directory/${path.path}"
    }

    private fun fileItem(name: String): ExplorerItem.RegularFile {
        val lookup = mockk<APathLookup<*>>().apply {
            every { lookedUp } returns LocalPath.build(File("/tmp/filter-test", name))
        }
        return ExplorerItem.RegularFile(lookup = lookup, mimeType = MimeInfo("text/plain"))
    }

    private fun directoryItem(name: String): ExplorerItem.RegularDirectory {
        val lookup = mockk<APathLookup<*>>().apply {
            every { lookedUp } returns LocalPath.build(File("/tmp/filter-test", name))
        }
        return ExplorerItem.RegularDirectory(lookup = lookup)
    }

    private fun names(items: List<ExplorerItem>) = items.map { (it as ExplorerItem.Path).path.name }

    @Test
    fun `include pattern keeps only matching names`() = runTest {
        val controller = controller()
        val items = listOf(fileItem("notes.txt"), fileItem("image.png"), fileItem("todo.txt"))

        // Simple (non-regex) mode is case-insensitive substring matching.
        val result = controller.applyFilters(
            items = items,
            filterState = FilterState(includePattern = ".TXT"),
            useRegexPatterns = false,
        )

        names(result) shouldContainExactly listOf("notes.txt", "todo.txt")
    }

    @Test
    fun `exclude pattern wins over include pattern`() = runTest {
        val controller = controller()
        val items = listOf(fileItem("notes.txt"), fileItem("secret.txt"))

        val result = controller.applyFilters(
            items = items,
            filterState = FilterState(includePattern = ".txt", excludePattern = "secret"),
            useRegexPatterns = false,
        )

        names(result) shouldContainExactly listOf("notes.txt")
    }

    @Test
    fun `files-only filter drops directories`() = runTest {
        val controller = controller()
        val items = listOf(fileItem("a.txt"), directoryItem("folder"))

        val result = controller.applyFilters(
            items = items,
            filterState = FilterState(fileTypeFilter = FileTypeFilter.FILES_ONLY),
            useRegexPatterns = false,
        )

        names(result) shouldContainExactly listOf("a.txt")
    }

    @Test
    fun `folders-only filter drops files`() = runTest {
        val controller = controller()
        val items = listOf(fileItem("a.txt"), directoryItem("folder"))

        val result = controller.applyFilters(
            items = items,
            filterState = FilterState(fileTypeFilter = FileTypeFilter.FOLDERS_ONLY),
            useRegexPatterns = false,
        )

        names(result) shouldContainExactly listOf("folder")
    }

    @Test
    fun `regex patterns are honored when enabled`() = runTest {
        val controller = controller()
        val items = listOf(fileItem("img_001.png"), fileItem("img_x.png"), fileItem("doc.txt"))

        val result = controller.applyFilters(
            items = items,
            filterState = FilterState(includePattern = "img_\\d+\\.png"),
            useRegexPatterns = true,
        )

        names(result) shouldContainExactly listOf("img_001.png")
    }

    @Test
    fun `view style updates immediately and persists async`() = runTest {
        val controller = controller()
        val grid = ExplorerViewStyle.Grid()

        controller.updateViewStyle(grid)

        controller.viewStyle.value shouldBe grid
        tabViewStore.currentViewStyle(workspaceId) shouldBe grid
        runCurrent()
        coVerify { styleStore.update(any()) }
        globalStyle.value shouldBe grid
    }

    @Test
    fun `filter state applies and resets`() = runTest {
        val controller = controller()
        val filter = FilterState(includePattern = "*.md")

        controller.applyFilterState(filter)
        controller.filterState.value shouldBe filter
        tabViewStore.currentFilter(workspaceId) shouldBe filter

        controller.resetFilters()
        controller.filterState.value shouldBe FilterState()
        viewPrefs.current(workspaceId, ExplorerTabViewStore.SLOT_FILTER) shouldBe null
    }

    /** A restored tab has to look like it did before the process died, from its very first state. */
    @Test
    fun `a restored tab starts with its own style and filters`() = runTest {
        val restoredFilter = FilterState(excludePattern = "tmp", fileTypeFilter = FileTypeFilter.FOLDERS_ONLY)
        val restoredStyle = ExplorerViewStyle.Grid(size = ExplorerViewStyle.Grid.GridSize.LARGE)
        tabViewStore.setViewStyle(workspaceId, restoredStyle)
        tabViewStore.setFilter(workspaceId, restoredFilter)

        val controller = controller()

        controller.viewStyle.value shouldBe restoredStyle
        controller.filterState.value shouldBe restoredFilter
    }

    @Test
    fun `a tab without stored preferences starts on the global default`() = runTest {
        val controller = controller()

        controller.viewStyle.value shouldBe initialStyle
        controller.filterState.value shouldBe FilterState()
    }

    @Test
    fun `one tab's view style does not follow another's`() = runTest {
        val tabA = Workspace.Id()
        val tabB = Workspace.Id()
        val grid = ExplorerViewStyle.Grid()
        val controllerA = controller(tabId = tabA)
        val controllerB = controller(tabId = tabB)

        controllerB.updateViewStyle(grid)

        controllerA.viewStyle.value shouldBe initialStyle
        controllerB.viewStyle.value shouldBe grid

        // What a process death plus session restore does to the registry
        val restored = viewPrefs.snapshot()
        viewPrefs.clear()
        viewPrefs.restore(restored)

        controller(tabId = tabA).viewStyle.value shouldBe initialStyle
        controller(tabId = tabB).viewStyle.value shouldBe grid
    }

    /** The global default still tracks the last used style, so a new tab opens the way the user left off. */
    @Test
    fun `a new tab opens with the last used style`() = runTest {
        val grid = ExplorerViewStyle.Grid()
        val controllerB = controller(tabId = Workspace.Id())

        controllerB.updateViewStyle(grid)
        runCurrent()

        controller(tabId = Workspace.Id()).viewStyle.value shouldBe grid
    }

    /** A tab that materialized its style must not be restyled by a later change of the default. */
    @Test
    fun `a global default change does not restyle an open tab`() = runTest {
        val controller = controller()

        globalStyle.value = ExplorerViewStyle.Grid()

        controller.viewStyle.value shouldBe initialStyle
        tabViewStore.currentViewStyle(workspaceId) shouldBe initialStyle
    }

    /** Nothing may render before the sort is known, or items would appear under the wrong order. */
    @Test
    fun `nothing is emitted before the lookup resolves`() = runTest {
        val blocked = CompletableDeferred<Unit>()
        val rules = mockRules().apply {
            every { observeRulesFor(any()) } returns flow {
                blocked.await()
                emit(emptyList())
            }
        }
        val controller = controller(rules = rules, location = flowOf(directory(dirA)))

        controller.resolvedSort.value shouldBe null
        runCurrent()
        controller.resolvedSort.value shouldBe null

        blocked.complete(Unit)
        runCurrent()
        controller.resolvedSort.value?.locationKey shouldBe "location://directory/${dirA.path}"
    }

    /**
     * flatMapLatest does not clear the last value of the combine downstream of it, so the resolution
     * has to name the location it belongs to - otherwise B's items would render under A's sort.
     */
    @Test
    fun `a navigation whose lookup is still pending does not report the previous folder's sort`() = runTest {
        val gateB = CompletableDeferred<Unit>()
        val rules = mockRules().apply {
            every { observeRulesFor(any()) } answers {
                val path = firstArg<LocalPath>()
                when (path) {
                    dirA -> flowOf(listOf(rule(dirA, bySize)))
                    else -> flow {
                        gateB.await()
                        emit(listOf(rule(dirB, byModified)))
                    }
                }
            }
        }
        val location = MutableStateFlow<ExplorerLocation?>(directory(dirA))
        val controller = controller(rules = rules, location = location)

        runCurrent()
        controller.resolvedSort.value?.resolution?.settings shouldBe bySize

        location.value = directory(dirB)
        runCurrent()
        // Still A's resolution; the pairing key is what keeps B's items from rendering under it
        controller.resolvedSort.value?.locationKey shouldBe "location://directory/${dirA.path}"

        gateB.complete(Unit)
        runCurrent()
        controller.resolvedSort.value?.locationKey shouldBe "location://directory/${dirB.path}"
        controller.resolvedSort.value?.resolution?.settings shouldBe byModified
    }

    /** A dead lookup must not leave the listing without a sort forever. */
    @Test
    fun `a failing rule lookup falls back instead of going silent`() = runTest {
        val rules = mockRules().apply {
            every { observeRulesFor(any()) } returns flow { throw IllegalStateException("db is gone") }
        }
        val controller = controller(rules = rules, location = flowOf(directory(dirA)))

        runCurrent()

        controller.resolvedSort.value?.resolution?.settings shouldBe initialSort
        controller.resolvedSort.value?.resolution?.winnerKey shouldBe null
    }

    @Test
    fun `a tab override wins over the saved rule at the same folder`() = runTest {
        val rules = mockRules { every { observeRulesFor(any()) } returns flowOf(listOf(rule(dirA, bySize))) }
        val controller = controller(rules = rules, location = flowOf(directory(dirA)))
        tabSortStore.update(workspaceId) {
            it.copy(
                rules = mapOf(
                    dirA.sortPathKey() to eu.darken.butler.explorer.core.sorting.rules.TabSortRule(
                        settings = byModified,
                        subtree = false,
                        path = "unreadable-on-purpose",
                    ),
                ),
            )
        }

        runCurrent()

        controller.resolvedSort.value?.resolution?.settings shouldBe byModified
        controller.resolvedSort.value?.resolution?.winnerLayer shouldBe SortRuleLayer.TAB
    }

    /** Home, Device and Trash have no path, so only the tab default and the global one apply. */
    @Test
    fun `a location without a path resolves to the tab default`() = runTest {
        val home = mockk<ExplorerLocation.Home>().apply {
            every { locationId } returns "location://home"
        }
        val controller = controller(location = flowOf(home))
        tabSortStore.update(workspaceId) { it.copy(default = bySize) }

        runCurrent()

        controller.resolvedSort.value?.locationKey shouldBe "location://home"
        controller.resolvedSort.value?.resolution?.settings shouldBe bySize
    }
}
