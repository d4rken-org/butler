package eu.darken.butler.common.compose

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class UserActivityTrackerTest : BaseTest() {

    private val window = 30.seconds

    @Test
    fun `starts active and goes idle when the window passes`() = runTest {
        val tracker = UserActivityTracker(testScheduler.timeSource)
        val seen = mutableListOf<Boolean>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { tracker.isActive(window).toList(seen) }

        runCurrent()
        seen shouldBe listOf(true)

        advanceTimeBy(window)
        runCurrent()
        seen shouldBe listOf(true, false)

        job.cancel()
    }

    @Test
    fun `an interaction ends the idle state`() = runTest {
        val tracker = UserActivityTracker(testScheduler.timeSource)
        val seen = mutableListOf<Boolean>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { tracker.isActive(window).toList(seen) }

        advanceTimeBy(window)
        runCurrent()
        seen shouldBe listOf(true, false)

        tracker.onUserInteraction()
        runCurrent()
        seen shouldBe listOf(true, false, true)

        job.cancel()
    }

    @Test
    fun `an interaction extends the window instead of merely restarting it`() = runTest {
        val tracker = UserActivityTracker(testScheduler.timeSource)
        val seen = mutableListOf<Boolean>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { tracker.isActive(window).toList(seen) }

        advanceTimeBy(20.seconds)
        runCurrent()
        tracker.onUserInteraction()

        // Past the original deadline, but the interaction moved it
        advanceTimeBy(15.seconds)
        runCurrent()
        seen shouldBe listOf(true)

        // A full window after that interaction it goes idle again, rather than staying awake forever
        advanceTimeBy(16.seconds)
        runCurrent()
        seen shouldBe listOf(true, false)

        job.cancel()
    }

    @Test
    fun `a late collector only gets what is left of the window`() = runTest {
        val tracker = UserActivityTracker(testScheduler.timeSource)
        advanceTimeBy(20.seconds)

        val seen = mutableListOf<Boolean>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { tracker.isActive(window).toList(seen) }

        runCurrent()
        seen shouldBe listOf(true)

        advanceTimeBy(10.seconds)
        runCurrent()
        seen shouldBe listOf(true, false)

        job.cancel()
    }

    @Test
    fun `a collector arriving after the window starts idle`() = runTest {
        val tracker = UserActivityTracker(testScheduler.timeSource)
        advanceTimeBy(window + 10.seconds)

        val seen = mutableListOf<Boolean>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { tracker.isActive(window).toList(seen) }

        runCurrent()
        seen shouldBe listOf(false)

        job.cancel()
    }
}
