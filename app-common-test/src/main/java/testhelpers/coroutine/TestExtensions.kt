package testhelpers.coroutine

import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The helper's own bookkeeping cancellation. Private type, so a test's own [CancellationException]
 * can never be mistaken for it, no matter what [expectedError] declares.
 */
private class AutoCancellation : CancellationException("autoCancel")

private fun Throwable.isAutoCancellation(): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth++ < 16) {
        if (current is AutoCancellation) return true
        current = current.cause
    }
    return false
}

fun runTest2(
    autoCancel: Boolean = false,
    context: CoroutineContext = EmptyCoroutineContext,
    expectedError: KClass<out Throwable>? = null,
    timeout: Duration = 60.seconds,
    testBody: suspend TestScope.() -> Unit
) {
    var sawExpectedError = false
    try {
        val scope = TestScope(context = context)
        try {
            scope.runTest(
                timeout = timeout
            ) {
                testBody()
                if (autoCancel) scope.cancel(AutoCancellation())
            }
        } catch (e: Throwable) {
            // The helper's own auto-cancellation is never an observation of expectedError, otherwise
            // e.g. expectedError = CancellationException::class would pass without the body throwing.
            if (e.isAutoCancellation()) throw e
            // Only the expected error type is swallowed, anything else is a real failure.
            val isExpected = expectedError?.isInstance(e) ?: false
            if (!isExpected) throw e
            // runTest also surfaces child coroutine failures during teardown, i.e. after the body
            // already returned, so the observation has to be tracked here, not at the body.
            sawExpectedError = true
        }
    } catch (e: CancellationException) {
        if (e.isAutoCancellation()) {
            log("test") { "Test was auto-cancelled ${e.asLog()}" }
        } else {
            throw e
        }
    }

    // A test that declares an expected error but never throws it is a silent pass, not a pass.
    if (expectedError != null && !sawExpectedError) {
        throw AssertionError("Expected ${expectedError.qualifiedName} to be thrown, but it never was")
    }
}

