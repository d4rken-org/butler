package eu.darken.butler.common.adb

import eu.darken.butler.common.error.causeChain

/**
 * A step of the Shizuku connect sequence used up its whole time budget without answering.
 *
 * Distinct from a plain [AdbException] because it says something a generic failure doesn't: we waited
 * the full budget and nothing happened. Repeating that attempt right away cannot fail differently, it
 * just spends the budget again, so callers may treat it as terminal instead of retryable.
 */
class AdbConnectTimeoutException @JvmOverloads constructor(
    message: String? = null,
    cause: Throwable? = null,
) : AdbException(message = message, cause = cause)

/**
 * Whether this failure is (or wraps) a spent connect budget.
 *
 * The walk is needed because callers wrap: `AdbServiceClient` turns the launcher's failure into an
 * `AdbUnavailableException` before anyone downstream sees it.
 *
 * Depth-bounded on purpose: [causeChain] follows `cause` until null without tracking identity, so a
 * cyclic chain would spin forever, on the very path whose job is to stop things hanging.
 */
fun Throwable.isAdbConnectTimeout(): Boolean = causeChain
    .take(MAX_CAUSE_DEPTH)
    .any { it is AdbConnectTimeoutException }

private const val MAX_CAUSE_DEPTH = 16
