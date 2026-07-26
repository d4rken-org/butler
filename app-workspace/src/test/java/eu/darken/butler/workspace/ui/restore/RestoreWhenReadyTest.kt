package eu.darken.butler.workspace.ui.restore

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The floating-bar shape of the restore: a readiness flag instead of an item count, and no
 * supersede arm. The scroll shape is covered by ScrollRestoreCoordinatorTest.
 */
class RestoreWhenReadyTest : BaseTest() {

    private val ready = mutableStateOf(false)
    private val applied = mutableListOf<Map<String, Float>>()

    private fun setReady() {
        ready.value = true
        Snapshot.sendApplyNotifications()
    }

    private suspend fun restore(
        saved: Map<String, Float>?,
        supersededBy: List<MutableSharedFlow<Unit>> = emptyList(),
        timeout: Duration = 5.seconds,
    ) = restoreWhenReady(
        saved = saved,
        isNoOp = { targets -> targets.isEmpty() || targets.values.all { it == 0f } },
        isReady = { ready.value },
        supersededBy = supersededBy,
        timeout = timeout,
        apply = { applied += it },
    )

    private val collapsed = mapOf("toolbar" to 1f)

    @Test
    fun `an absent value needs no restore`() = runTest {
        restore(null) shouldBe Outcome.NOT_NEEDED
        applied shouldBe emptyList()
    }

    @Test
    fun `an all-default value needs no restore`() = runTest {
        restore(mapOf("toolbar" to 0f)) shouldBe Outcome.NOT_NEEDED
        applied shouldBe emptyList()
    }

    @Test
    fun `waits for readiness before applying`() = runTest {
        val outcome = async { restore(collapsed) }
        runCurrent()
        applied shouldBe emptyList()

        setReady()
        advanceUntilIdle()

        outcome.await() shouldBe Outcome.APPLIED
        applied shouldBe listOf(collapsed)
    }

    /** If the UI never materializes, the saved value must survive rather than be overwritten. */
    @Test
    fun `never becoming ready times out without applying`() = runTest {
        val outcome = async { restore(collapsed) }
        advanceUntilIdle()

        outcome.await() shouldBe Outcome.TIMED_OUT
        applied shouldBe emptyList()
    }

    /**
     * The floating bar restore waits without a bound: a page can build its stack states long before
     * it renders a bar, and giving up would permanently disarm both restore and recording.
     */
    @Test
    fun `an infinite timeout keeps waiting and still applies`() = runTest {
        val outcome = async { restore(collapsed, timeout = Duration.INFINITE) }
        advanceUntilIdle()
        applied shouldBe emptyList()

        setReady()
        advanceUntilIdle()

        outcome.await() shouldBe Outcome.APPLIED
        applied shouldBe listOf(collapsed)
    }

    @Test
    fun `an intent flow supersedes the restore`() = runTest {
        val intent = MutableSharedFlow<Unit>()
        val outcome = async { restore(collapsed, supersededBy = listOf(intent)) }
        runCurrent()

        intent.emit(Unit)
        advanceUntilIdle()

        outcome.await() shouldBe Outcome.SUPERSEDED
        applied shouldBe emptyList()
    }
}
