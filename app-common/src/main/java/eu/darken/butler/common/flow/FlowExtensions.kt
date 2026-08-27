package eu.darken.butler.common.flow

import eu.darken.butler.common.coroutine.cancelAfterRun
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.error.hasCause
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformWhile
import kotlin.time.Duration

/**
 * Create a stateful flow, with the initial value of null, but never emits a null value.
 * Helper method to create a new flow without suspending and without initial value
 * The flow collector will just wait for the first value
 */
fun <T : Any> Flow<T>.shareLatest(
    scope: CoroutineScope,
    started: SharingStarted = SharingStarted.WhileSubscribed(replayExpirationMillis = 0),
    tag: String? = null,
) = this
    .onStart { if (tag != null) log(tag, VERBOSE) { "shareLatest(...) start" } }
    .onEach { if (tag != null) log(tag, VERBOSE) { "shareLatest(...) emission: $it" } }
    .onCompletion { if (tag != null) log(tag, VERBOSE) { "shareLatest(...) completed." } }
    .catch {
        if (tag != null) log(tag, VERBOSE) { "shareLatest(...) catch(): ${it.asLog()}" }
        throw it
    }
    .stateIn(
        scope = scope,
        started = started,
        initialValue = null
    )
    .filterNotNull()

fun <T : Any?> Flow<T>.replayingShare(scope: CoroutineScope) = this.shareIn(
    scope = scope,
    replay = 1,
    started = SharingStarted.WhileSubscribed(replayExpiration = Duration.ZERO)
)

fun <T> Flow<T>.withPrevious(): Flow<Pair<T?, T>> = this
    .scan(Pair<T?, T?>(null, null)) { previous, current -> Pair(previous.second, current) }
    .drop(1)
    .map {
        @Suppress("UNCHECKED_CAST")
        it as Pair<T?, T>
    }


fun <T> Flow<T>.onError(block: suspend (Throwable) -> Unit) = this.catch {
    block(it)
    throw it
}

fun <T> Flow<T>.takeUntilAfter(predicate: suspend (T) -> Boolean) = transformWhile {
    val fullfilled = predicate(it)
    emit(it)
    !fullfilled // We keep emitting until condition is fullfilled = true
}

/**
 * Renders a flow emission for a log line.
 *
 * Only self-describing scalars are printed by value. Everything else degrades to its type name
 * (plus element count for collections/maps), because these lines reach two places a raw
 * `toString()` must not: logcat, and the bug-report file a user attaches to an issue. An Explorer
 * state emission, for example, carries the whole directory listing.
 *
 * Numbers are excluded from by-value printing on purpose: this helper cannot tell a list size from
 * an account id or a PIN, and it is applied to every flow in the app. A call site that knows its
 * number is safe should log it itself.
 *
 *     true                    -> "true"
 *     Type.EXPLORER           -> "EXPLORER"
 *     42                      -> "Int"
 *     Directory(items=[...])  -> "Directory"
 *     listOf(a, b, c)         -> "ArrayList(3)"
 */
private fun Any?.asFlowLogValue(): String = when (this) {
    null -> "null"
    is Boolean -> toString()
    // name, not toString(): an enum may override toString() to render a user-facing value.
    is Enum<*> -> name
    is Collection<*> -> "${this::class.simpleName}($size)"
    is Map<*, *> -> "${this::class.simpleName}($size)"
    else -> this::class.simpleName ?: this::class.java.name
}

/**
 * @param enabled evaluated per emission, not once at flow construction: callers gate on
 *   [eu.darken.butler.common.debug.Bugs] flags that the user toggles long after DI built the flow.
 */
fun <T> Flow<T>.setupCommonEventHandlers(
    tag: String,
    enabled: () -> Boolean = { true },
    identifier: () -> String,
) = this
    .onStart { if (enabled()) log(tag, VERBOSE) { "${identifier()}.onStart()" } }
    .onEach { if (enabled()) log(tag, VERBOSE) { "${identifier()}.onEach(): ${it.asFlowLogValue()}" } }
    .onCompletion { if (enabled()) log(tag, VERBOSE) { "${identifier()}.onCompletion()" } }
    .catch {
        if (it.hasCause(CancellationException::class)) {
            if (enabled()) log(tag, VERBOSE) { "${identifier()} cancelled" }
        } else {
            log(tag, ERROR) { "${identifier()} failed: ${it.asLog()}" }
            throw it
        }
    }

suspend fun <T> Flow<*>.launchForAction(scope: CoroutineScope, action: suspend () -> T): T = this
    .launchIn(scope).cancelAfterRun(action)