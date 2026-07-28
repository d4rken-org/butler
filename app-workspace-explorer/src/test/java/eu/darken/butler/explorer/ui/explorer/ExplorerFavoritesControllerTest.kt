package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.explorer.core.favorites.ExplorerFavoritesRepo
import eu.darken.butler.explorer.core.favorites.FavoriteFeedback
import eu.darken.butler.explorer.core.favorites.FavoriteItem
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
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
        coEvery { removeAllForUndo(any()) } answers {
            val paths = firstArg<List<APath<*>>>()
            removedIndex
                ?.let { idx -> paths.map { ExplorerFavoritesRepo.RemovedFavorite(it, idx) } }
                ?: emptyList()
        }
        coEvery { addAll(any()) } answers { firstArg() }
        coEvery { addAllAt(any()) } just Runs
    }

    private fun CoroutineScope.controller(
        repo: ExplorerFavoritesRepo = mockRepo(),
        isPickerActive: () -> Boolean = { false },
        revealFavorite: suspend (APath<*>) -> Unit = {},
    ) = ExplorerFavoritesController(
        favoritesRepo = repo,
        scope = this,
        doLaunch = { block -> launch { block() } },
        isPickerActive = isPickerActive,
        revealFavorite = revealFavorite,
        tag = "test",
    )

    @Test
    fun `removeFromHome queues an undo prompt with the original index`() = runTest {
        val controller = controller()

        controller.removeFromHome(favorite("docs"))
        runCurrent()

        val removed = controller.feedback.value.shouldBeInstanceOf<FavoriteFeedback.Removed>()
        removed.removed.single().path.path shouldBe path("docs").path
        removed.removed.single().originalIndex shouldBe 2
    }

    @Test
    fun `removeFromHome is ignored in picker mode`() = runTest {
        val controller = controller(isPickerActive = { true })

        controller.removeFromHome(favorite("docs"))
        runCurrent()

        controller.feedback.value shouldBe null
    }

    @Test
    fun `feedback expires after the timeout`() = runTest {
        val controller = controller()

        controller.removeFromHome(favorite("docs"))
        runCurrent()
        controller.feedback.value shouldNotBe null

        advanceTimeBy(ExplorerFavoritesController.FEEDBACK_TIMEOUT)
        runCurrent()

        controller.feedback.value shouldBe null
    }

    @Test
    fun `newer feedback supersedes the pending one and survives the stale timer`() = runTest {
        val controller = controller()

        controller.removeFromHome(favorite("first"))
        runCurrent()
        val first = controller.feedback.value!!

        // Stagger the second removal so the two timer windows don't coincide.
        advanceTimeBy(2.seconds)
        controller.removeFromHome(favorite("second"))
        runCurrent()
        val second = controller.feedback.value!!
        second.id shouldNotBe first.id
        second.shouldBeInstanceOf<FavoriteFeedback.Removed>()
            .removed.single().path.path shouldBe path("second").path

        // Cross the FIRST removal's timeout boundary: its (cancelled) timer must not
        // clear the newer feedback, whose own window is still open.
        advanceTimeBy(ExplorerFavoritesController.FEEDBACK_TIMEOUT - 1.seconds)
        runCurrent()
        controller.feedback.value shouldBe second

        // The second removal's own window then expires normally.
        advanceTimeBy(2.seconds)
        runCurrent()
        controller.feedback.value shouldBe null
    }

    @Test
    fun `acting on removal feedback restores the original positions and clears the bar`() = runTest {
        val repo = mockRepo(removedIndex = 7)
        val controller = controller(repo = repo)

        controller.removeFromHome(favorite("docs"))
        runCurrent()

        controller.onFeedbackAction()
        runCurrent()

        coVerify {
            repo.addAllAt(
                match { entries ->
                    entries.single().path.path == path("docs").path && entries.single().originalIndex == 7
                }
            )
        }
        controller.feedback.value shouldBe null
    }

    @Test
    fun `acting on superseded feedback leaves the newer bar's own timeout intact`() = runTest {
        val repo = mockRepo()
        val restoreGate = CompletableDeferred<Unit>()
        coEvery { repo.addAllAt(any()) } coAnswers { restoreGate.await() }
        val controller = controller(repo = repo)

        controller.removeFromHome(favorite("old"))
        runCurrent()
        // Undo starts but hangs inside the storage write.
        controller.onFeedbackAction()
        runCurrent()

        // Newer feedback opens its own window while that write is still in flight.
        controller.addAll(listOf(path("new")))
        runCurrent()
        val newer = controller.feedback.value.shouldBeInstanceOf<FavoriteFeedback.Added>()

        restoreGate.complete(Unit)
        runCurrent()
        controller.feedback.value shouldBe newer

        // The newer feedback must still expire on its own — not hang around forever.
        advanceTimeBy(ExplorerFavoritesController.FEEDBACK_TIMEOUT)
        runCurrent()
        controller.feedback.value shouldBe null
    }

    @Test
    fun `removal of an untracked path clears any stale feedback`() = runTest {
        val trackedRepo = mockRepo(removedIndex = 1)
        val controller = controller(repo = trackedRepo)
        controller.removeFromHome(favorite("tracked"))
        runCurrent()
        controller.feedback.value shouldNotBe null

        coEvery { trackedRepo.removeAllForUndo(any()) } returns emptyList()
        controller.removeFromHome(favorite("untracked"))
        runCurrent()

        controller.feedback.value shouldBe null
    }

    @Test
    fun `clearFeedback drops the bar without restoring`() = runTest {
        val repo = mockRepo()
        val controller = controller(repo = repo)

        controller.removeFromHome(favorite("docs"))
        runCurrent()
        controller.feedback.value shouldNotBe null

        controller.clearFeedback()

        controller.feedback.value shouldBe null
        coVerify(exactly = 0) { repo.addAllAt(any()) }
    }

    @Test
    fun `addAll confirms the paths that were actually added`() = runTest {
        val controller = controller()

        controller.addAll(listOf(path("a"), path("b")))
        runCurrent()

        val added = controller.feedback.value.shouldBeInstanceOf<FavoriteFeedback.Added>()
        added.paths.map { it.path } shouldContainExactly listOf(path("a").path, path("b").path)
        added.count shouldBe 2
    }

    @Test
    fun `addAll shows nothing when every path was already a favorite`() = runTest {
        val repo = mockRepo()
        coEvery { repo.addAll(any()) } returns emptyList()
        val controller = controller(repo = repo)

        controller.addAll(listOf(path("a")))
        runCurrent()

        controller.feedback.value shouldBe null
    }

    @Test
    fun `acting on add feedback reveals the first added favorite`() = runTest {
        val revealed = mutableListOf<APath<*>>()
        val controller = controller(revealFavorite = { revealed.add(it) })

        controller.addAll(listOf(path("a"), path("b")))
        runCurrent()
        controller.onFeedbackAction()
        runCurrent()

        revealed.map { it.path } shouldContainExactly listOf(path("a").path)
        controller.feedback.value shouldBe null
    }

    @Test
    fun `toggleCurrent surfaces the resulting direction and keeps removals undoable`() = runTest {
        val repo = mockRepo()
        val target = path("current")
        coEvery { repo.toggle(target) } returns ExplorerFavoritesRepo.ToggleResult.Added(target)
        val controller = controller(repo = repo)

        controller.toggleCurrent(target)
        runCurrent()
        controller.feedback.value.shouldBeInstanceOf<FavoriteFeedback.Added>()
            .paths.single().path shouldBe target.path

        coEvery { repo.toggle(target) } returns ExplorerFavoritesRepo.ToggleResult.Removed(
            ExplorerFavoritesRepo.RemovedFavorite(target, 3)
        )
        controller.toggleCurrent(target)
        runCurrent()
        controller.feedback.value.shouldBeInstanceOf<FavoriteFeedback.Removed>()
            .removed.single().originalIndex shouldBe 3

        controller.onFeedbackAction()
        runCurrent()
        coVerify { repo.addAllAt(match { it.single().originalIndex == 3 }) }
    }

    @Test
    fun `action-bar mutations are ignored in picker mode`() = runTest {
        val repo = mockRepo()
        val controller = controller(repo = repo, isPickerActive = { true })

        controller.addAll(listOf(path("a")))
        controller.removeAll(listOf(path("a")))
        controller.toggleCurrent(path("a"))
        runCurrent()

        controller.feedback.value shouldBe null
        coVerify(exactly = 0) { repo.addAll(any()) }
        coVerify(exactly = 0) { repo.removeAllForUndo(any()) }
        coVerify(exactly = 0) { repo.toggle(any()) }
    }
}
