package testhelpers.coroutine

import io.kotest.assertions.throwables.shouldThrow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class RunTest2Test : BaseTest() {

    @Test fun `expected error thrown by the test body itself`() {
        runTest2(expectedError = IllegalStateException::class) {
            throw IllegalStateException("thrown by body")
        }
    }

    @Test fun `expected error thrown by a child coroutine, surfacing at teardown`() {
        runTest2(expectedError = IllegalStateException::class) {
            launch {
                delay(1000)
                throw IllegalStateException("thrown by child")
            }
        }
    }

    @Test fun `expected error that never happens is a failure`() {
        shouldThrow<AssertionError> {
            runTest2(expectedError = IllegalStateException::class) {
                delay(1000)
            }
        }
    }

    @Test fun `the auto cancellation does not count as an expected error`() {
        // The helper cancels the scope itself, that must never be mistaken for the test body
        // throwing the expected CancellationException.
        shouldThrow<AssertionError> {
            runTest2(autoCancel = true, expectedError = CancellationException::class) {
                delay(1000)
            }
        }
    }

    @Test fun `a cancellation thrown by the test body is still an expected error`() {
        runTest2(autoCancel = true, expectedError = CancellationException::class) {
            throw CancellationException("thrown by body")
        }
    }

    @Test fun `auto cancel without an expected error passes`() {
        runTest2(autoCancel = true) {
            delay(1000)
        }
    }
}
