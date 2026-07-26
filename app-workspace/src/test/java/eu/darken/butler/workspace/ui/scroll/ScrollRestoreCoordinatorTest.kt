package eu.darken.butler.workspace.ui.scroll

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import eu.darken.butler.workspace.ui.restore.Outcome
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Duration.Companion.seconds

class ScrollRestoreCoordinatorTest : BaseTest() {

    private class FakeScrollTarget(itemCount: Int = 0) : ScrollTarget {

        private val itemCountState = mutableStateOf(itemCount)
        private val scrollInProgressState = mutableStateOf(false)
        private val dragEvents = MutableStateFlow<Interaction?>(null)

        var scrolledTo: WorkspaceScrollPosition? = null
            private set

        override val totalItemsCount: Int
            get() = itemCountState.value

        override var position: WorkspaceScrollPosition = WorkspaceScrollPosition()

        override val isScrollInProgress: Boolean
            get() = scrollInProgressState.value

        override val interactions: Flow<Interaction> = dragEvents.filterNotNull()

        override suspend fun scrollTo(position: WorkspaceScrollPosition) {
            scrolledTo = position
            this.position = position
        }

        fun fill(count: Int) {
            itemCountState.value = count
            Snapshot.sendApplyNotifications()
        }

        fun drag() {
            dragEvents.value = DragInteraction.Start()
        }

        /** What a caller's `scrollToItem` does: moves the position and reports a scroll. */
        fun scrollProgrammaticallyTo(target: WorkspaceScrollPosition) {
            position = target
            scrollInProgressState.value = true
            Snapshot.sendApplyNotifications()
        }

        /** A scroll nobody asked the coordinator for, e.g. a sort change scrolling back to top. */
        fun scrollElsewhere() {
            scrollInProgressState.value = true
            Snapshot.sendApplyNotifications()
        }
    }

    @Test
    fun `an absent saved position needs no restore`() = runTest {
        val target = FakeScrollTarget(itemCount = 100)

        target.restore(null) shouldBe Outcome.NOT_NEEDED
        target.scrolledTo shouldBe null
    }

    @Test
    fun `a saved top position needs no restore`() = runTest {
        val target = FakeScrollTarget(itemCount = 100)

        target.restore(WorkspaceScrollPosition()) shouldBe Outcome.NOT_NEEDED
        target.scrolledTo shouldBe null
    }

    @Test
    fun `content arriving seconds later still restores`() = runTest {
        val target = FakeScrollTarget()
        val restore = async { target.restore(WorkspaceScrollPosition(50, 7), timeout = 5.seconds) }

        advanceTimeBy(3.seconds)
        runCurrent()
        target.scrolledTo shouldBe null

        target.fill(100)
        advanceUntilIdle()

        restore.await() shouldBe Outcome.APPLIED
        target.scrolledTo shouldBe WorkspaceScrollPosition(50, 7)
    }

    @Test
    fun `a placeholder-only list does not satisfy the wait`() = runTest {
        // Several pages render a single loading/empty item - "not empty" is not "has the saved index"
        val target = FakeScrollTarget(itemCount = 1)
        val restore = async { target.restore(WorkspaceScrollPosition(100), timeout = 5.seconds) }

        advanceUntilIdle()

        restore.await() shouldBe Outcome.TIMED_OUT
        target.scrolledTo shouldBe null
    }

    @Test
    fun `content that never arrives times out without applying`() = runTest {
        val target = FakeScrollTarget()
        val restore = async { target.restore(WorkspaceScrollPosition(20), timeout = 5.seconds) }

        advanceUntilIdle()

        restore.await() shouldBe Outcome.TIMED_OUT
        target.scrolledTo shouldBe null
    }

    @Test
    fun `a drag while waiting supersedes the restore`() = runTest {
        val target = FakeScrollTarget()
        val restore = async { target.restore(WorkspaceScrollPosition(50), timeout = 5.seconds) }
        runCurrent()

        target.drag()
        advanceUntilIdle()

        restore.await() shouldBe Outcome.SUPERSEDED
        target.scrolledTo shouldBe null
    }

    /**
     * A guarded scroll-to-top effect (sort change, new search, view-style transfer) scrolls without
     * any drag interaction. Restoring over it afterwards would override what the user just asked for.
     */
    @Test
    fun `a programmatic scroll while waiting supersedes the restore`() = runTest {
        val target = FakeScrollTarget()
        val restore = async { target.restore(WorkspaceScrollPosition(50), timeout = 5.seconds) }
        runCurrent()

        target.scrollElsewhere()
        advanceUntilIdle()
        target.fill(100)
        advanceUntilIdle()

        restore.await() shouldBe Outcome.SUPERSEDED
        target.scrolledTo shouldBe null
    }

    @Test
    fun `a drag after the content arrived does not undo the restore`() = runTest {
        val target = FakeScrollTarget()
        val restore = async { target.restore(WorkspaceScrollPosition(50), timeout = 5.seconds) }
        runCurrent()

        target.fill(100)
        advanceUntilIdle()
        target.drag()
        advanceUntilIdle()

        restore.await() shouldBe Outcome.APPLIED
        target.scrolledTo shouldBe WorkspaceScrollPosition(50)
    }

    // region Arming recording after a timeout

    /**
     * The regression this guards: a state that is hoisted but never attached to a lazy container
     * (Explorer keeps a list and a grid state, only one of which is attached) can never satisfy the
     * readiness predicate, so it always times out. Arming recording on a drag alone then drops the
     * list/grid transfer's programmatic scroll for good, and re-entry restores the stale position.
     */
    @Test
    fun `after a timeout a programmatic scroll arms recording`() = runTest {
        val target = FakeScrollTarget()
        target.restore(WorkspaceScrollPosition(50), timeout = 5.seconds) shouldBe Outcome.TIMED_OUT

        val armed = async { target.awaitMovement() }
        runCurrent()
        armed.isCompleted shouldBe false

        target.scrollProgrammaticallyTo(WorkspaceScrollPosition(12, 3))
        advanceUntilIdle()

        armed.isCompleted shouldBe true
        // The position a recorder would now persist is the transferred one, not the stale saved one
        target.position shouldBe WorkspaceScrollPosition(12, 3)
    }

    @Test
    fun `after a timeout a drag still arms recording`() = runTest {
        val target = FakeScrollTarget()
        target.restore(WorkspaceScrollPosition(50), timeout = 5.seconds) shouldBe Outcome.TIMED_OUT

        val armed = async { target.awaitMovement() }
        runCurrent()
        armed.isCompleted shouldBe false

        target.drag()
        advanceUntilIdle()

        armed.isCompleted shouldBe true
    }

    @Test
    fun `an untouched list never arms recording`() = runTest {
        val target = FakeScrollTarget()
        target.restore(WorkspaceScrollPosition(50), timeout = 5.seconds) shouldBe Outcome.TIMED_OUT

        val armed = async { target.awaitMovement() }
        advanceUntilIdle()

        // Nothing moved it, so the saved position must stay untouched rather than be overwritten
        armed.isCompleted shouldBe false
        armed.cancel()
    }

    // endregion
}
