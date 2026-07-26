package testhelpers

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A [Clock] whose time only moves when the test moves it.
 *
 * Lets time-throttled production code be asserted exactly instead of against wall-clock ranges:
 * - a frozen clock (default) means no time-based throttle window ever elapses
 * - [autoAdvance] moves the clock on every read, e.g. to make every throttle window elapse
 */
class TestClock(
    start: Instant = Instant.fromEpochMilliseconds(0),
    private val autoAdvance: Duration = Duration.ZERO,
) : Clock {

    @Volatile
    var current: Instant = start

    override fun now(): Instant = current.also { current += autoAdvance }

    operator fun plusAssign(duration: Duration) {
        current += duration
    }
}
