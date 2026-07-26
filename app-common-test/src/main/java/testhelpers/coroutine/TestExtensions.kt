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

fun runTest2(
    autoCancel: Boolean = false,
    context: CoroutineContext = EmptyCoroutineContext,
    expectedError: KClass<out Throwable>? = null,
    timeout: Duration = 60.seconds,
    testBody: suspend TestScope.() -> Unit
) {
    var bodyCompleted = false
    try {
        val scope = TestScope(context = context)
        try {
            scope.runTest(
                timeout = timeout
            ) {
                testBody()
                bodyCompleted = true
                if (autoCancel) scope.cancel("autoCancel")
            }
        } catch (e: Throwable) {
            // Only the expected error type is swallowed, anything else is a real failure.
            val isExpected = expectedError?.isInstance(e) ?: false
            if (!isExpected) throw e
        }
    } catch (e: CancellationException) {
        if (e.message == "autoCancel" && autoCancel) {
            log("test") { "Test was auto-cancelled ${e.asLog()}" }
        } else {
            throw e
        }
    }

    // A test that declares an expected error but never throws it is a silent pass, not a pass.
    if (expectedError != null && bodyCompleted) {
        throw AssertionError("Expected ${expectedError.qualifiedName} to be thrown, but the test body completed normally")
    }
}

