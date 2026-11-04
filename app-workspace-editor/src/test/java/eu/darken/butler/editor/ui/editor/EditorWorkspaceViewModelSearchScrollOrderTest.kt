package eu.darken.butler.editor.ui.editor

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.editor.core.EditorWorkspace
import eu.darken.butler.editor.core.engine.SearchResult
import eu.darken.butler.editor.core.engine.TextPosition
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

/**
 * DIAG: does the page ever see search Next's new scrollTrigger together with the OLD cursor?
 * The trigger and the cursor reach the VM state through separate upstream flows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorWorkspaceViewModelSearchScrollOrderTest : BaseTest() {

    private val workspaceId = Workspace.Id()

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun teardown() {
        Dispatchers.resetMain()
    }

    private val results = listOf(10L, 20L, 30L).map {
        SearchResult(position = TextPosition(offset = it * 10L, line = it, column = 0), matchText = "x")
    }

    private val wsState = MutableStateFlow<EditorWorkspace.State>(
        EditorWorkspace.State.Ready(
            EditorWorkspace.EditorState(
                totalLines = 1_000L,
                cursorPosition = results[0].position,
                searchQuery = "x",
                searchResults = results,
            ),
        ),
    )

    private fun publishCursor(position: TextPosition) = wsState.update { current ->
        val ready = current as EditorWorkspace.State.Ready
        ready.copy(editor = ready.editor.copy(cursorPosition = position))
    }

    /** [gate] null = uncontended (cursor published without suspending). */
    private fun makeWorkspace(gate: CompletableDeferred<Unit>?): EditorWorkspace = mockk<EditorWorkspace>().apply {
        every { info } returns MutableStateFlow(
            Workspace.Info(id = workspaceId, type = Workspace.Type.EDITOR, title = "test".toCaString()),
        )
        every { state } returns wsState
        coEvery { setCursorPosition(any()) } coAnswers {
            gate?.await()
            publishCursor(firstArg())
        }
    }

    private fun makeViewModel(workspace: EditorWorkspace, dispatcher: CoroutineDispatcher?) = EditorWorkspaceViewModel(
        id = workspaceId,
        dispatchers = TestDispatcherProvider(dispatcher),
        workspaceProvider = mockk<WorkspaceProvider> { every { retrieve(workspaceId) } returns flowOf(workspace) },
        workspaceRemote = mockk<WorkspaceRemote> {
            every { events } returns emptyFlow()
            every { state } returns emptyFlow()
        },
        clipboardHelper = mockk(relaxed = true),
        clipboardRepo = mockk(relaxed = true),
        filenameValidator = mockk(relaxed = true),
    )

    private data class Emission(val trigger: Int, val cursorLine: Long, val targetLine: Long? = null)

    /**
     * [pump] runs the dispatcher(s) the VM and the collector use without advancing virtual time:
     * the VM's own tickers would make advanceUntilIdle spin forever.
     */
    private fun TestScope.runNext(
        label: String,
        gate: CompletableDeferred<Unit>?,
        vmDispatcher: CoroutineDispatcher?,
        collectorDispatcher: CoroutineDispatcher,
        pump: () -> Unit,
    ): List<Emission> {
        val vm = makeViewModel(makeWorkspace(gate), vmDispatcher)
        val emissions = mutableListOf<Emission>()
        // Own scope: runTest's scopes reject a dispatcher on a foreign scheduler
        val collectorScope = CoroutineScope(collectorDispatcher + Job())
        collectorScope.launch {
            vm.state.collect { emissions += Emission(it.scrollTrigger, it.cursorPosition.line, it.scrollTargetLine) }
        }
        pump()
        emissions.last() shouldBe Emission(0, 10L)
        val beforeNext = emissions.size

        vm.onPageAction(EditorPageAction.Search.NextResult)
        pump()
        val beforeRelease = emissions.size
        gate?.complete(Unit)
        pump()
        collectorScope.cancel()

        println(
            "DIAG [$label] emissions (trigger, cursorLine): $emissions; " +
                "Next sent after index ${beforeNext - 1}; gate released after index ${beforeRelease - 1}",
        )
        emissions.last() shouldBe Emission(1, 20L, 20L)
        return emissions
    }

    private fun List<Emission>.mixed() = filter { it.trigger == 1 && it.cursorLine == 10L }

    @Test
    fun `contended - setCursorPosition suspends - unconfined`() = runTest {
        val emissions = runNext(
            "contended/unconfined",
            CompletableDeferred(),
            vmDispatcher = null,
            collectorDispatcher = UnconfinedTestDispatcher(testScheduler),
            pump = { testScheduler.runCurrent() },
        )
        withClue("an emission with the new trigger but the old cursor must name the new target") {
            emissions.mixed().filter { it.targetLine != 20L }.shouldBeEmpty()
        }
    }

    @Test
    fun `contended - setCursorPosition suspends - standard dispatcher`() = runTest {
        val scheduler = TestCoroutineScheduler()
        val d = StandardTestDispatcher(scheduler)
        val emissions = runNext(
            "contended/standard",
            CompletableDeferred(),
            vmDispatcher = d,
            collectorDispatcher = d,
            pump = { scheduler.runCurrent() },
        )
        withClue("an emission with the new trigger but the old cursor must name the new target") {
            emissions.mixed().filter { it.targetLine != 20L }.shouldBeEmpty()
        }
    }

    @Test
    fun `uncontended - unconfined`() = runTest {
        val emissions = runNext(
            "uncontended/unconfined",
            gate = null,
            vmDispatcher = null,
            collectorDispatcher = UnconfinedTestDispatcher(testScheduler),
            pump = { testScheduler.runCurrent() },
        )
        withClue("an emission with the new trigger but the old cursor must name the new target") {
            emissions.mixed().filter { it.targetLine != 20L }.shouldBeEmpty()
        }
    }

    @Test
    fun `uncontended - standard dispatcher`() = runTest {
        val scheduler = TestCoroutineScheduler()
        val d = StandardTestDispatcher(scheduler)
        val emissions = runNext(
            "uncontended/standard",
            gate = null,
            vmDispatcher = d,
            collectorDispatcher = d,
            pump = { scheduler.runCurrent() },
        )
        withClue("an emission with the new trigger but the old cursor must name the new target") {
            emissions.mixed().filter { it.targetLine != 20L }.shouldBeEmpty()
        }
    }
}
