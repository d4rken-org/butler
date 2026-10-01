package eu.darken.butler.e2e

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettledClickTest {

    @Test
    fun reacquiresAfterStaleInitialBounds() {
        val stale = node()
        every { stale.visibleBounds } throws StaleObjectException()
        assertRecovers(stale)
    }

    @Test
    fun reacquiresAfterStaleLaterBounds() {
        val stale = node()
        var reads = 0
        every { stale.visibleBounds } answers {
            if (++reads == 1) BOUNDS else throw StaleObjectException()
        }
        assertRecovers(stale)
    }

    @Test
    fun reacquiresAfterStaleLookup() {
        val clock = Clock()
        val fresh = node()
        var finds = 0
        clock.click(find = { if (++finds == 1) throw StaleObjectException() else fresh })
        assertEquals(2, finds)
        verify(exactly = 1) { fresh.click() }
    }

    @Test
    fun reacquiresAfterStaleMetadata() {
        val stale = node()
        every { stale.contentDescription } throws StaleObjectException()
        assertRecovers(stale)
    }

    @Test
    fun returnsMetadataFromNodeWhoseClickSucceeds() {
        val stale = node("Next")
        every { stale.click() } throws StaleObjectException()
        assertRecovers(stale, attemptedClicks = 1)
    }

    @Test
    fun doesNotReadOrClickAgainAfterSuccessfulGesture() {
        val clock = Clock()
        val target = node("Next")
        var clicked = false
        every { target.visibleBounds } answers {
            check(!clicked)
            BOUNDS
        }
        every { target.contentDescription } answers {
            check(!clicked)
            "Next"
        }
        every { target.click() } answers { clicked = true }
        assertFalse(clock.click(find = { target }))
        assertTrue(clicked)
        verify(exactly = 1) { target.click() }
        verify(exactly = 1) { target.contentDescription }
    }

    @Test
    fun staleRetriesShareDeadlineAndPreserveCause() {
        val clock = Clock()
        val stale = node()
        val cause = StaleObjectException()
        every { stale.visibleBounds } throws cause
        val budgets = mutableListOf<Long>()
        val error = assertThrows(AssertionError::class.java) {
            clock.click(find = { remaining ->
                budgets += remaining
                clock.time += 40
                stale
            })
        }
        assertEquals(listOf(1_000L, 810L, 620L, 430L, 240L, 50L), budgets)
        assertEquals(1_000L, clock.time)
        assertSame(cause, error.cause)
        assertTrue(error.message!!.contains("target"))
        assertTrue(error.message!!.contains("stale node retry"))
        verify(exactly = 0) { stale.click() }
    }

    @Test
    fun missingTargetUsesOnlyRemainingBudget() {
        val clock = Clock()
        val budgets = mutableListOf<Long>()
        val error = assertThrows(AssertionError::class.java) {
            clock.click(find = { remaining ->
                budgets += remaining
                clock.time += remaining
                null
            })
        }
        assertEquals(listOf(1_000L), budgets)
        assertEquals(1_000L, clock.time)
        assertTrue(error.message!!.contains("target not found"))
        assertTrue(error.message!!.contains(By.text("target").toString()))
    }

    @Test
    fun movingOrEmptyBoundsNeverClick() {
        for (moving in listOf(false, true)) {
            val clock = Clock()
            val target = node()
            var reads = 0
            every { target.visibleBounds } answers {
                if (moving) Rect(++reads, 0, reads + 10, 10) else Rect()
            }
            val error = assertThrows(AssertionError::class.java) { clock.click(find = { target }) }
            assertEquals(1_000L, clock.time)
            val lastBounds = if (moving) Rect(reads, 0, reads + 10, 10) else Rect()
            assertTrue(error.message!!.contains("bounds never settled (last bounds: $lastBounds)"))
            assertTrue(error.message!!.contains(By.text("target").toString()))
            verify(exactly = 0) { target.click() }
        }
    }

    @Test
    fun blockingReadsCannotStartGesturePastDeadline() {
        for (expireInMetadata in listOf(false, true)) {
            val clock = Clock()
            val target = node()
            if (expireInMetadata) {
                every { target.contentDescription } answers {
                    clock.time = 1_001
                    "Done"
                }
            } else {
                every { target.visibleBounds } answers {
                    clock.time = 1_001
                    BOUNDS
                }
            }
            val error = assertThrows(AssertionError::class.java) { clock.click(find = { target }) }
            val reason = if (expireInMetadata) {
                "bounds settled; click not completed"
            } else {
                "bounds never settled (last bounds: $BOUNDS)"
            }
            assertTrue(error.message!!.contains(reason))
            verify(exactly = 0) { target.click() }
        }
    }

    @Test
    fun nonStaleClickFailureIsNotRetried() {
        val clock = Clock()
        val target = node()
        val cause = IllegalStateException("gesture failed")
        every { target.click() } throws cause
        assertSame(cause, assertThrows(IllegalStateException::class.java) {
            clock.click(find = { target })
        })
        verify(exactly = 1) { target.click() }
    }

    private fun assertRecovers(stale: UiObject2, attemptedClicks: Int = 0) {
        val clock = Clock()
        val fresh = node("Done")
        var finds = 0
        assertTrue(clock.click(find = { if (++finds == 1) stale else fresh }))
        assertEquals(2, finds)
        verify(exactly = attemptedClicks) { stale.click() }
        verify(exactly = 2) { fresh.visibleBounds }
        verify(exactly = 1) { fresh.contentDescription }
        verify(exactly = 1) { fresh.click() }
    }

    private fun node(description: String = "Next"): UiObject2 = mockk {
        every { visibleBounds } returns BOUNDS
        every { contentDescription } returns description
        every { click() } returns Unit
    }

    private class Clock {
        var time = 0L

        fun click(find: (Long) -> UiObject2?): Boolean = clickWhenSettled(
            selector = By.text("target"),
            timeoutMs = 1_000,
            findNode = find,
            beforeClick = { it.contentDescription == "Done" },
            now = { time },
            pause = { time += it },
        )
    }

    companion object {
        private val BOUNDS = Rect(10, 20, 100, 200)
    }
}
