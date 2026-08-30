package eu.darken.butler.searcher.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.searcher.core.engine.SearchEngine
import eu.darken.butler.searcher.core.operations.SearcherCommand
import eu.darken.butler.workspace.contracts.searcher.ContentQuery
import eu.darken.butler.workspace.contracts.searcher.FilenameQuery
import eu.darken.butler.workspace.contracts.searcher.SearchTarget
import eu.darken.butler.workspace.contracts.searcher.SearcherArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.filesystem.FileSystemEvent
import eu.darken.butler.workspace.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.label
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.OperationsManager
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.error.recordingIncidentStore

/**
 * The live workspace must publish its [Workspace.Info] through the same derivation the paused
 * stand-in uses - at the seed AND on every later query or target change - otherwise a restored tab
 * disagrees with its stand-in, or keeps advertising state it no longer holds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearcherWorkspaceSeedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The engine's live target list; the workspace reads its identity subtitle from it. */
    private val engineTargets = MutableStateFlow(emptyList<SearchTarget>())

    private val downloads = SearchTarget.Path(path = LocalPath.build("/sdcard/Download"))
    private val pictures = SearchTarget.Path(path = LocalPath.build("/sdcard/Pictures"))

    private fun makeWorkspace(
        arguments: SearcherArguments,
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(),
        id: Workspace.Id = Workspace.Id(),
    ) = SearcherWorkspace(
        id = id,
        creationArguments = arguments,
        dispatcherProvider = TestDispatcherProvider(dispatcher),
        issueHandler = mockk(relaxed = true),
        operationsManager = mockk<OperationsManager>(relaxed = true).apply {
            every { operations } returns MutableStateFlow(emptyList<ManagedOperation>())
        },
        deleteOperationFactory = mockk(relaxed = true),
        searchEngineFactory = mockk<SearchEngine.Factory>(relaxed = true).apply {
            every { create(any(), any()) } returns mockk<SearchEngine>(relaxed = true).apply {
                every { targetState } returns engineTargets
                coEvery { search(any(), any()) } returns SearchEngine.Result.NoTargets
            }
        },
        fileSystemHinter = mockk<FileSystemHinter>(relaxed = true).apply {
            // Hinter events are a MutableSharedFlow; a fresh one simply never emits
            every { events } returns MutableSharedFlow<FileSystemEvent>()
        },
        folderPreviewResolver = mockk(relaxed = true),
        errorIncidentStore = recordingIncidentStore(),
    )

    private fun assertSeedMatchesDerivation(arguments: SearcherArguments) {
        val derived = deriveSearcherDisplay(arguments)
        val seed = makeWorkspace(arguments).info.value

        seed.title.get(context) shouldBe (derived?.title?.get(context) ?: Workspace.Type.SEARCHER.label.get(context))
        seed.subtitle?.get(context) shouldBe derived?.subtitle?.get(context)
    }

    @Test
    fun `a restored search seeds the derived query and targets`() {
        assertSeedMatchesDerivation(
            SearcherArguments.Default(
                filenameQuery = FilenameQuery(pattern = "*.pdf"),
                startTargets = listOf(downloads),
            ),
        )
    }

    @Test
    fun `a fresh search tab falls back to the type label`() {
        assertSeedMatchesDerivation(SearcherArguments.Default())
    }

    @Test
    fun `the title is never the internal debug label`() {
        val id = Workspace.Id()
        val workspace = makeWorkspace(SearcherArguments.Default(), id = id)

        workspace.info.value.title.get(context) shouldNotContain id.shortTag
        workspace.info.value.title.get(context) shouldBe Workspace.Type.SEARCHER.label.get(context)
    }

    @Test
    fun `running a search renames the tab after the query and its targets`() = runTest {
        engineTargets.value = listOf(downloads)
        val workspace = makeWorkspace(SearcherArguments.Default(), UnconfinedTestDispatcher(testScheduler))

        workspace.execute(
            SearcherCommand.Search(
                filenameQuery = FilenameQuery(pattern = "*.mp3"),
                targets = listOf(downloads),
            )
        )

        workspace.info.value.title.get(context) shouldBe "*.mp3"
        workspace.info.value.subtitle!!.get(context) shouldBe "/sdcard/Download"
        workspace.release()
    }

    @Test
    fun `a content-only search is named after the content pattern`() = runTest {
        val workspace = makeWorkspace(SearcherArguments.Default(), UnconfinedTestDispatcher(testScheduler))

        workspace.execute(
            SearcherCommand.Search(
                contentQuery = ContentQuery(pattern = "TODO"),
                targets = emptyList(),
            )
        )

        workspace.info.value.title.get(context) shouldBe "TODO"
        // The subtitle describes where the search runs; there are no targets to describe here
        workspace.info.value.subtitle shouldBe null
        workspace.release()
    }

    @Test
    fun `targets loaded before the observer subscribes still describe the tab`() = runTest {
        // The engine restores saved/default targets asynchronously; that can land before the
        // workspace's own identity observer starts collecting
        engineTargets.value = listOf(downloads)

        val workspace = makeWorkspace(SearcherArguments.Default(), UnconfinedTestDispatcher(testScheduler))

        workspace.info.value.subtitle!!.get(context) shouldBe "/sdcard/Download"
        // Contract: what a restore of this tab would show must equal what the live tab shows
        val restored = deriveSearcherDisplay(workspace.createArguments())
        restored!!.subtitle!!.get(context) shouldBe workspace.info.value.subtitle!!.get(context)
        workspace.release()
    }

    @Test
    fun `editing the targets republishes the tab identity`() = runTest {
        engineTargets.value = listOf(downloads)
        val workspace = makeWorkspace(
            SearcherArguments.Default(filenameQuery = FilenameQuery(pattern = "*.pdf")),
            UnconfinedTestDispatcher(testScheduler),
        )

        engineTargets.value = listOf(downloads, pictures)

        workspace.info.value.title.get(context) shouldBe "*.pdf"
        workspace.info.value.subtitle!!.get(context) shouldBe "/sdcard/Download, /sdcard/Pictures"
        workspace.release()
    }

    @Test
    fun `clearing drops the query from the tab identity`() = runTest {
        engineTargets.value = listOf(downloads)
        val workspace = makeWorkspace(SearcherArguments.Default(), UnconfinedTestDispatcher(testScheduler))
        workspace.execute(
            SearcherCommand.Search(
                filenameQuery = FilenameQuery(pattern = "*.mp3"),
                targets = listOf(downloads),
            )
        )

        workspace.execute(SearcherCommand.Clear)

        // Stale query text must not survive the clear; the remaining targets still describe the tab
        workspace.info.value.title.get(context) shouldBe Workspace.Type.SEARCHER.label.get(context)
        workspace.info.value.subtitle!!.get(context) shouldBe "/sdcard/Download"
        workspace.release()
    }

    @Test
    fun `the live identity matches what a restore of the same tab would show`() = runTest {
        engineTargets.value = listOf(downloads)
        val workspace = makeWorkspace(SearcherArguments.Default(), UnconfinedTestDispatcher(testScheduler))
        workspace.execute(
            SearcherCommand.Search(
                filenameQuery = FilenameQuery(pattern = "*.mp3"),
                targets = listOf(downloads),
            )
        )

        val restored = deriveSearcherDisplay(workspace.createArguments())

        restored!!.title!!.get(context) shouldBe workspace.info.value.title.get(context)
        restored.subtitle!!.get(context) shouldBe workspace.info.value.subtitle!!.get(context)
        workspace.release()
    }
}
