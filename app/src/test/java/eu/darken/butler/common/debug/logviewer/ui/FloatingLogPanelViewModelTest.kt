package eu.darken.butler.common.debug.logviewer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.debug.DebugSettings
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logviewer.core.LogHistoryRecorder
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

class FloatingLogPanelViewModelTest : BaseTest() {

    private lateinit var recorder: LogHistoryRecorder
    private lateinit var visibleFlow: MutableStateFlow<Boolean>
    private lateinit var debugFlow: MutableStateFlow<Boolean>
    private lateinit var visibleValue: DataStoreValue<Boolean>
    private lateinit var debugValue: DataStoreValue<Boolean>
    private lateinit var debugSettings: DebugSettings
    private val context: Context = mockk(relaxed = true)

    @BeforeEach
    fun setup() {
        Logging.clearAll()
        recorder = LogHistoryRecorder()
        visibleFlow = MutableStateFlow(false)
        debugFlow = MutableStateFlow(false)
        visibleValue = mockk {
            every { flow } returns visibleFlow
            coEvery { update(any()) } returns DataStoreValue.Updated(old = true, new = false)
        }
        debugValue = mockk {
            every { flow } returns debugFlow
        }
        debugSettings = mockk {
            every { floatingLogVisible } returns visibleValue
            every { isDebugMode } returns debugValue
        }
    }

    @AfterEach
    fun tearDown() = Logging.clearAll()

    private fun createViewModel() = FloatingLogPanelViewModel(
        dispatcherProvider = TestDispatcherProvider(),
        context = context,
        debugSettings = debugSettings,
        recorder = recorder,
    )

    private fun emit(message: String, priority: Logging.Priority = Logging.Priority.DEBUG) =
        recorder.log(priority, "TestTag", message, null)

    @Test
    fun `isRendered requires debug mode, not just the toggle`() = runTest {
        val vm = createViewModel()
        visibleFlow.value = true

        vm.isRendered.first() shouldBe false

        debugFlow.value = true
        vm.isRendered.first { it } shouldBe true
    }

    @Test
    fun `capture is active only while visible, debug and started`() = runTest {
        val vm = createViewModel()
        Logging.loggers.contains(recorder) shouldBe false

        visibleFlow.value = true
        debugFlow.value = true
        Logging.loggers.contains(recorder) shouldBe false

        vm.setLifecycleStarted(true)
        Logging.loggers.contains(recorder) shouldBe true

        vm.setLifecycleStarted(false)
        Logging.loggers.contains(recorder) shouldBe false

        vm.setLifecycleStarted(true)
        Logging.loggers.contains(recorder) shouldBe true

        debugFlow.value = false
        Logging.loggers.contains(recorder) shouldBe false
    }

    @Test
    fun `level selection is a display filter and never touches the shared capture floor`() = runTest {
        val vm = createViewModel()
        emit("dbg")
        emit("err", Logging.Priority.ERROR)

        vm.setDisplayPriority(Logging.Priority.ERROR)

        recorder.minPriority shouldBe Logging.Priority.DEBUG
        val state = vm.state.first { it.lines.size == 1 }
        state.lines.single().message shouldBe "err"
        state.displayPriority shouldBe Logging.Priority.ERROR
    }

    @Test
    fun `pause freezes the display while capture continues`() = runTest {
        val vm = createViewModel()
        emit("a")
        vm.state.first { it.lines.size == 1 }

        vm.togglePause()
        emit("b")

        val paused = vm.state.first { it.isPaused && it.pausedNewCount == 1 }
        paused.lines.map { it.message } shouldBe listOf("a")
        // Nothing was lost: the shared buffer kept recording.
        recorder.snapshot().map { it.message } shouldBe listOf("a", "b")

        vm.togglePause()
        vm.state.first { !it.isPaused }.lines.map { it.message } shouldBe listOf("a", "b")
    }

    @Test
    fun `search parks on the newest match and steps with wrap-around`() = runTest {
        val vm = createViewModel()
        emit("foo one")
        emit("bar")
        emit("foo two")

        vm.setQuery("foo")

        val state = vm.state.first { it.matchCount == 2 }
        state.currentOrdinal shouldBe 2

        vm.nextMatch()
        vm.state.first { it.currentOrdinal == 1 }

        vm.prevMatch()
        vm.state.first { it.currentOrdinal == 2 }
    }

    @Test
    fun `a parked match survives query refinement while it still matches`() = runTest {
        val vm = createViewModel()
        emit("foo alpha")
        emit("foo beta")
        emit("foo alpha two")

        vm.setQuery("foo")
        vm.state.first { it.matchCount == 3 }.currentOrdinal shouldBe 3

        vm.prevMatch()
        vm.state.first { it.currentOrdinal == 2 }.currentMatchLineId shouldBe 1L

        // Refining the query keeps the parked line as long as it still matches.
        vm.setQuery("foo b")
        val state = vm.state.first { it.matchCount == 1 }
        state.currentMatchLineId shouldBe 1L
        state.currentOrdinal shouldBe 1
    }

    @Test
    fun `copyAll truncates to the cap and reports it`() = runTest {
        // ClipData.newPlainText is an android.jar stub that throws on the JVM, and the relaxed
        // Context mock returns a bare Object for the erased getSystemService(Class) generic.
        mockkStatic(ClipData::class)
        try {
            every { ClipData.newPlainText(any(), any()) } returns mockk()
            every { context.getSystemService(ClipboardManager::class.java) } returns mockk(relaxed = true)

            val vm = createViewModel()
            val total = FloatingLogPanelViewModel.COPY_LINE_CAP + 5
            repeat(total) { emit("line $it") }
            vm.state.first { it.lines.size == total }

            vm.copyAll()

            val event = vm.events.first() as FloatingLogPanelViewModel.Event.Copied
            event.truncatedBy shouldBe 5
        } finally {
            unmockkStatic(ClipData::class)
        }
    }

    @Test
    fun `clearBuffer empties the display and resets the match`() = runTest {
        val vm = createViewModel()
        emit("foo")
        vm.setQuery("foo")
        vm.state.first { it.matchCount == 1 }

        vm.clearBuffer()

        val state = vm.state.first { it.lines.isEmpty() }
        state.matchCount shouldBe 0
        state.currentMatchLineId shouldBe null
    }

    @Test
    fun `clearBuffer while paused re-freezes on the emptied buffer`() = runTest {
        val vm = createViewModel()
        emit("a")
        vm.state.first { it.lines.size == 1 }
        vm.togglePause()

        vm.clearBuffer()

        val state = vm.state.first { it.lines.isEmpty() }
        state.isPaused shouldBe true
    }

    @Test
    fun `close switches the setting off`() = runTest {
        val update = slot<(Boolean) -> Boolean?>()
        coEvery { visibleValue.update(capture(update)) } returns DataStoreValue.Updated(old = true, new = false)

        createViewModel().close()

        update.captured(true) shouldBe false
    }
}
