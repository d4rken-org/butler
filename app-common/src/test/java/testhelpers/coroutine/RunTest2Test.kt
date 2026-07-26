package testhelpers.coroutine

import io.kotest.assertions.throwables.shouldThrow
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
}
