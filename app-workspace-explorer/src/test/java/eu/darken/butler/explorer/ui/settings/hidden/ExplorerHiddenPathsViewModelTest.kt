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
}
