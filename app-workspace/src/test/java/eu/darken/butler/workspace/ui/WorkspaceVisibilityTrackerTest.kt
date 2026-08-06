package eu.darken.butler.workspace.ui

import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Duration.Companion.seconds

/**
 * The tracker has to answer two different questions: what is on screen right now, and what has been
 * on screen since somebody last looked. Only the second one survives a swipe that starts and ends
 * between two auto-pause evaluations.
 */
class WorkspaceVisibilityTrackerTest : BaseTest() {

    private val first = Workspace.Id()
    private val second = Workspace.Id()
    private val third = Workspace.Id()

    @Test
    fun `publishing exposes the visible set`() {
        val tracker = WorkspaceVisibilityTracker()
        val token = tracker.claim()

        tracker.visibleIds() shouldBe emptySet()

        tracker.publish(token, setOf(first, second))

        tracker.visibleIds() shouldBe setOf(first, second)
    }

    @Test
    fun `a sighting is recorded even when the set goes back to what it was`() {
        val tracker = WorkspaceVisibilityTracker()
        val token = tracker.claim()
        tracker.publish(token, setOf(first))

        val before = tracker.seenStamps(listOf(first, second))

        // A complete drag: the neighbour was on screen and is not any more
        tracker.publish(token, setOf(first, second))
        tracker.publish(token, setOf(first))

        tracker.visibleIds() shouldBe setOf(first)
        tracker.wasSeenSince(before) shouldBe true
        tracker.wasSeenSince(tracker.seenStamps(listOf(first, second))) shouldBe false
    }

    @Test
    fun `a workspace that was never published carries no sighting`() {
        val tracker = WorkspaceVisibilityTracker()
        val token = tracker.claim()

        val before = tracker.seenStamps(listOf(third))
        tracker.publish(token, setOf(first, second))

        before[third] shouldBe 0L
        tracker.wasSeenSince(before) shouldBe false
    }

    @Test
    fun `releasing clears the visible set`() {
        val tracker = WorkspaceVisibilityTracker()
        val token = tracker.claim()
        tracker.publish(token, setOf(first))

        tracker.release(token)

        tracker.visibleIds() shouldBe emptySet()
    }

    @Test
    fun `a stale publisher can neither publish nor clear the current one`() {
        val tracker = WorkspaceVisibilityTracker()
        val stale = tracker.claim()

        // A rapid disposal/recreation: the replacement claims before the old one is torn down
        val current = tracker.claim()
        tracker.publish(current, setOf(first))

        tracker.publish(stale, setOf(second, third))
        tracker.visibleIds() shouldBe setOf(first)

        tracker.release(stale)
        tracker.visibleIds() shouldBe setOf(first)
    }

    @Test
    fun `a guarded unit that is published again is announced`() = runTest {
        val tracker = WorkspaceVisibilityTracker()
        val token = tracker.claim()
        tracker.guardPaused(first)

        tracker.publish(token, setOf(first))

        val announced = withTimeout(5.seconds) { tracker.reappeared.first() }
        announced shouldBe first
    }

    @Test
    fun `a retired unit is not announced`() = runTest {
        val tracker = WorkspaceVisibilityTracker()
        val token = tracker.claim()
        tracker.guardPaused(first)
        tracker.guardPaused(second)

        tracker.retirePaused(listOf(first))
        tracker.publish(token, setOf(first, second))

        // Only the still-guarded one is announced, so the retired record really was dropped
        val announced = withTimeout(5.seconds) { tracker.reappeared.take(1).toList() }
        announced shouldContainExactly listOf(second)
    }

    @Test
    fun `forgetting drops bookkeeping for workspaces that are gone`() {
        val tracker = WorkspaceVisibilityTracker()
        val token = tracker.claim()
        tracker.publish(token, setOf(first, second))

        tracker.forget(setOf(first))

        tracker.seenStamps(listOf(second))[second] shouldBe 0L
        (tracker.seenStamps(listOf(first))[first]!! > 0L) shouldBe true
    }
}
