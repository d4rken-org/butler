package eu.darken.butler.common.coroutine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable


/**
 * Runs [block] detached on this scope and waits at most [timeoutMs] for its result, `null` on timeout.
 *
 * For *synchronous* calls that can wedge (binder transactions against an alive-but-unresponsive
 * process), [withTimeoutOrNull] on its own is not enough: it only unwinds at suspension points, so a
 * thread blocked inside the call keeps pinning the caller anyway. Detaching the call means a wedged
 * thread leaks instead of the caller hanging forever, the same trade-off
 * `AdbHostLauncher.createConnection` makes for `bindUserService()`.
 *
 * [T] is non-nullable so a `null` return unambiguously means "timed out". Exceptions thrown by
 * [block] propagate to the caller.
 *
 * The receiver MUST be a scope independent of the caller (e.g. `@AppScope`). Passing a scope the
 * caller is a child of defeats the whole point: the wedge would pin the caller again.
 */
suspend fun <T : Any> CoroutineScope.runDetachedWithTimeout(
    dispatcher: CoroutineDispatcher,
    timeoutMs: Long,
    block: () -> T,
): T? {
    val deferred = async(dispatcher) { block() }
    return try {
        withTimeoutOrNull(timeoutMs) { deferred.await() }
    } finally {
        // In the finally, not just on the timeout branch: withTimeoutOrNull only converts its OWN
        // timeout, so a cancelled caller propagates out of await() and would otherwise leave the
        // detached coroutine running unobserved on the long-lived scope. Cancelling can't interrupt a
        // thread already blocked in a binder transaction, but it does drop queued/cooperative work.
        deferred.cancel()
    }
}

/**
 * Opens a resource whose ownership transfers to the caller, and closes it instead of leaking it when
 * the caller is no longer there to receive it.
 *
 * Two separate things can go wrong. A cancellation arriving while [open] runs would make
 * `withContext` discard whatever the block returned, so the open runs under [NonCancellable].
 * A cancellation arriving as the block hands its result back is reported on only one of
 * `withContext`'s two resume paths, so the reference is kept out here and the caller's cancellation
 * is checked explicitly. Either way nobody downstream ever receives the resource, which makes this
 * the only place it can still be closed.
 */
suspend fun <T : Closeable> openForHandover(
    dispatcher: CoroutineDispatcher,
    open: suspend () -> T?,
): T? {
    var opened: T? = null
    try {
        withContext(NonCancellable + dispatcher) { opened = open() }
        currentCoroutineContext().ensureActive()
        return opened
    } catch (e: CancellationException) {
        runCatching { opened?.close() }
        throw e
    }
}

suspend fun <T> Job.cancelAfterRun(action: suspend () -> T): T = try {
    action()
} finally {
    cancel()
}

suspend fun <T : Closeable, R> T.use(block: suspend T.() -> R): R = try {
    block(this)
} finally {
    close()
}
