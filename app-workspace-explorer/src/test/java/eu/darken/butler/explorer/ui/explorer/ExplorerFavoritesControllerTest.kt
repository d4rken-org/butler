package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.core.favorites.ExplorerFavoritesRepo
import eu.darken.butler.explorer.core.favorites.FavoriteItem
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.File
import kotlin.time.Duration.Companion.seconds

class ExplorerFavoritesControllerTest : BaseTest() {

    private fun path(name: String) = LocalPath.build(File("/tmp/fav-test", name))

    private fun favorite(name: String) = FavoriteItem(
        path = path(name),
        state = FavoriteItem.State.Resolving,
    )

    private fun mockRepo(removedIndex: Int? = 2): ExplorerFavoritesRepo = mockk<ExplorerFavoritesRepo>().apply {
        coEvery { removeForUndo(any()) } answers {
            removedIndex?.let { ExplorerFavoritesRepo.RemovedFavorite(firstArg(), it) }
        }
        coEvery { addAt(any(), any()) } just Runs
    }

    private fun CoroutineScope.controller(
        repo: ExplorerFavoritesRepo = mockRepo(),
        isPickerActive: () -> Boolean = { false },
    ) = ExplorerFavoritesController(
        favoritesRepo = repo,
        scope = this,
        doLaunch = { block -> launch { block() } },
        isPickerActive = isPickerActive,
        tag = "test",
    )

    @Test
    fun `remove queues an undo prompt with the original index`() = runTest {
        val controller = controller()

        controller.remove(favorite("docs"))
        runCurrent()

        val pending = controller.pendingRemoval.value
        pending shouldNotBe null
        pending!!.path.path shouldBe path("docs").path
        pending.originalIndex shouldBe 2
    }

    @Test
    fun `remove is ignored in picker mode`() = runTest {
        val controller = controller(isPickerActive = { true })

        controller.remove(favorite("docs"))
        runCurrent()

        controller.pendingRemoval.value shouldBe null
    }

    @Test
    fun `pending removal expires after the undo timeout`() = runTest {
        val controller = controller()

        controller.remove(favorite("docs"))
        runCurrent()
        controller.pendingRemoval.value shouldNotBe null

        advanceTimeBy(ExplorerFavoritesController.UNDO_TIMEOUT)
        runCurrent()

        controller.pendingRemoval.value shouldBe null
    }

    @Test
    fun `newer removal supersedes the pending one and survives the stale timer`() = runTest {
        val controller = controller()

        controller.remove(favorite("first"))
        runCurrent()
        val first = controller.pendingRemoval.value!!

        // Stagger the second removal so the two timer windows don't coincide.
        advanceTimeBy(2.seconds)
        controller.remove(favorite("second"))
        runCurrent()
        val second = controller.pendingRemoval.value!!
        second.id shouldNotBe first.id
        second.path.path shouldBe path("second").path

        // Cross the FIRST removal's timeout boundary: its (cancelled) timer must not
        // clear the newer pending entry, whose own window is still open.
        advanceTimeBy(ExplorerFavoritesController.UNDO_TIMEOUT - 1.seconds)
        runCurrent()
        controller.pendingRemoval.value shouldBe second

        // The second removal's own window then expires normally.
        advanceTimeBy(2.seconds)
        runCurrent()
        controller.pendingRemoval.value shouldBe null
    }

    @Test
    fun `undo restores at the original position and clears the prompt`() = runTest {
        val repo = mockRepo(removedIndex = 7)
        val controller = controller(repo = repo)

        controller.remove(favorite("docs"))
        runCurrent()

        controller.undo()
        runCurrent()

        coVerify { repo.addAt(match { it.path == path("docs").path }, 7) }
        controller.pendingRemoval.value shouldBe null
    }

    @Test
    fun `remove of an untracked path clears any stale prompt`() = runTest {
        val trackedRepo = mockRepo(removedIndex = 1)
        val controller = controller(repo = trackedRepo)
        controller.remove(favorite("tracked"))
        runCurrent()
        controller.pendingRemoval.value shouldNotBe null

        coEvery { trackedRepo.removeForUndo(any()) } returns null
        controller.remove(favorite("untracked"))
        runCurrent()

        controller.pendingRemoval.value shouldBe null
    }

    @Test
    fun `finalize pending removal clears without restoring`() = runTest {
        val repo = mockRepo()
        val controller = controller(repo = repo)

        controller.remove(favorite("docs"))
        runCurrent()
        controller.pendingRemoval.value shouldNotBe null

        controller.finalizePendingRemoval()

        controller.pendingRemoval.value shouldBe null
        coVerify(exactly = 0) { repo.addAt(any(), any()) }
    }
}
