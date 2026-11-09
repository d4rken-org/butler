package eu.darken.butler.explorer.ui.settings.hidden

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.core.hidden.ExplorerHiddenPathsRepo
import eu.darken.butler.explorer.core.hidden.HiddenEntry
import eu.darken.butler.explorer.core.hidden.HiddenPaths
import eu.darken.butler.explorer.core.sorting.rules.sortPathKey
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

class ExplorerHiddenPathsViewModelTest : BaseTest() {

    private val existing = LocalPath.build("/storage/emulated/0/Download/report.pdf")
    private val gone = LocalPath.build("/storage/emulated/0/Removed/folder")
    private val existingEntry = HiddenEntry(existing.sortPathKey(), existing)
    private val goneEntry = HiddenEntry(gone.sortPathKey(), gone)

    private val hiddenState = MutableStateFlow<HiddenPaths?>(HiddenPaths(listOf(existingEntry, goneEntry)))

    private val repo: ExplorerHiddenPathsRepo = mockk {
        every { state } returns hiddenState
        coEvery { unhideKeys(any()) } answers {
            val keys = firstArg<Set<String>>()
            hiddenState.value = HiddenPaths(hiddenState.value!!.entries.filterNot { it.key in keys })
        }
    }

    private fun createViewModel() = ExplorerHiddenPathsViewModel(
        dispatcherProvider = TestDispatcherProvider(),
        hiddenPaths = repo,
    )

    private suspend fun ExplorerHiddenPathsViewModel.current() = state.filterNotNull().first()

    private suspend fun ExplorerHiddenPathsViewModel.awaitState(
        predicate: (ExplorerHiddenPathsViewModel.State) -> Boolean,
    ) = state.filterNotNull().first(predicate)

    @Test
    fun `lists every stored entry in stored order`() = runTest {
        createViewModel().current().entries shouldBe listOf(existingEntry, goneEntry)
    }

    @Test
    fun `an empty list is a ready state, not a loading one`() = runTest {
        hiddenState.value = HiddenPaths.EMPTY

        createViewModel().current().entries shouldBe emptyList()
    }

    @Test
    fun `removing an entry unhides exactly its stored key`() = runTest {
        val vm = createViewModel()
        vm.current()

        vm.remove(goneEntry)

        coVerify(exactly = 1) { repo.unhideKeys(setOf(goneEntry.key)) }
        vm.state.filterNotNull().first { it.entries.size == 1 }.entries shouldBe listOf(existingEntry)
    }

    @Test
    fun `the query matches file names case-insensitively`() = runTest {
        val vm = createViewModel()
        vm.openSearch()

        vm.setQuery("REPORT")

        vm.current().apply {
            isSearching shouldBe true
            query shouldBe "REPORT"
            entries shouldBe listOf(existingEntry, goneEntry)
            visibleEntries shouldBe listOf(existingEntry)
        }
    }

    @Test
    fun `the query matches the full path case-insensitively`() = runTest {
        val vm = createViewModel()
        vm.openSearch()

        vm.setQuery("removed/fol")

        vm.current().visibleEntries shouldBe listOf(goneEntry)
    }

    @Test
    fun `closing search clears the query and restores the full list`() = runTest {
        val vm = createViewModel()
        vm.openSearch()
        vm.setQuery("report")

        vm.closeSearch()

        vm.current().apply {
            isSearching shouldBe false
            query shouldBe ""
            visibleEntries shouldBe listOf(existingEntry, goneEntry)
        }
    }

    @Test
    fun `unhide all without a query unhides every entry in one call`() = runTest {
        val vm = createViewModel()

        vm.requestUnhideAll()
        vm.current().confirmUnhideCount shouldBe 2
        vm.confirmUnhideAll()

        coVerify(exactly = 1) { repo.unhideKeys(setOf(existingEntry.key, goneEntry.key)) }
        coVerify(exactly = 1) { repo.unhideKeys(any()) }
        vm.awaitState { it.entries.isEmpty() && it.confirmUnhideCount == null }
    }

    @Test
    fun `unhide all with a query unhides only the matching entries and keeps the query`() = runTest {
        val vm = createViewModel()
        vm.openSearch()
        vm.setQuery("report")

        vm.requestUnhideAll()
        vm.current().confirmUnhideCount shouldBe 1
        vm.confirmUnhideAll()

        coVerify(exactly = 1) { repo.unhideKeys(setOf(existingEntry.key)) }
        coVerify(exactly = 1) { repo.unhideKeys(any()) }
        vm.awaitState { it.entries == listOf(goneEntry) && it.confirmUnhideCount == null }.apply {
            visibleEntries shouldBe emptyList()
            query shouldBe "report"
            isSearching shouldBe true
        }
    }

    @Test
    fun `dismissing the unhide all confirmation unhides nothing`() = runTest {
        val vm = createViewModel()

        vm.requestUnhideAll()
        vm.dismissUnhideAll()
        vm.confirmUnhideAll()

        coVerify(exactly = 0) { repo.unhideKeys(any()) }
        vm.current().apply {
            confirmUnhideCount shouldBe null
            entries shouldBe listOf(existingEntry, goneEntry)
        }
    }

    @Test
    fun `the unhide all batch is fixed when the confirmation is requested`() = runTest {
        val vm = createViewModel()
        vm.openSearch()
        vm.setQuery("report")
        vm.requestUnhideAll()

        val added = LocalPath.build("/storage/emulated/0/Download/report-2.pdf")
        val addedEntry = HiddenEntry(added.sortPathKey(), added)
        hiddenState.value = HiddenPaths(listOf(existingEntry, goneEntry, addedEntry))
        vm.setQuery("")
        vm.confirmUnhideAll()

        coVerify(exactly = 1) { repo.unhideKeys(setOf(existingEntry.key)) }
        coVerify(exactly = 1) { repo.unhideKeys(any()) }
    }
}
