package eu.darken.butler.explorer.ui.explorer

import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.ui.explorer.elements.favoriteContentIndex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Lazy index a reveal of [request] should scroll to in this emission.
 *
 * @return `null` while this emission cannot answer - the listing is still loading, it belongs to
 *         another location than the reveal was aimed at, or it does not hold the requested path.
 *
 * A destination-scoped reveal additionally refuses the loader's peek stage: peek rows carry the
 * destination's own location id, but they are raw listing order, so an index resolved from them
 * points at a different row than the one the final ordering puts the path at.
 */
internal fun ExplorerWorkspaceViewModel.State.revealIndexFor(
    request: ExplorerWorkspaceViewModel.RevealRequest,
): Int? {
    if (request.destination != null) {
        if (request.destination != listingLocationId) return null
        if (items?.any { it is ExplorerItem.Peek } == true) return null
    }
    val index = when (request.scope) {
        // Favorites live in a trailing section, not in `items`.
        ExplorerWorkspaceViewModel.RevealRequest.Scope.Favorites -> favoriteContentIndex(request.path)
        ExplorerWorkspaceViewModel.RevealRequest.Scope.Items -> items?.indexOfFirst { item ->
            when (item) {
                is ExplorerItem.Path -> item.path.path == request.path.path
                else -> false
            }
        }
    }
    return index?.takeIf { it >= 0 }
}

/** Where a reveal stands in one emission. */
internal sealed interface RevealWait {
    /** Answered: [index] is where the path is, or null when this emission does not hold it. */
    data class Resolved(val index: Int?) : RevealWait

    /** The destination is on screen but its listing is not settled yet. */
    data object Pending : RevealWait

    /** The destination will not produce the row anymore. */
    data object Abandoned : RevealWait
}

/**
 * A reveal flagged with [ExplorerWorkspaceViewModel.RevealRequest.awaitDestinationLoad] waits out a
 * load of its destination ([RevealWait.Pending]) and gives up once the user has left it, or once it
 * settled without a listing (e.g. a permission is missing). Any other request is always resolved.
 */
internal fun ExplorerWorkspaceViewModel.State.revealWait(
    request: ExplorerWorkspaceViewModel.RevealRequest,
): RevealWait {
    val destination = request.destination
    if (!request.awaitDestinationLoad || destination == null) return RevealWait.Resolved(revealIndexFor(request))

    if (locationId != destination) return RevealWait.Abandoned
    val location = currentLocation
    val loading = location?.isLoading == true
    if (!loading && location is ExplorerLocation.Directory && location.items == null) return RevealWait.Abandoned
    val listingSettled = listingLocationId == destination && items.orEmpty().none { it is ExplorerItem.Peek }
    if (loading || !listingSettled) return RevealWait.Pending

    return RevealWait.Resolved(revealIndexFor(request))
}

internal sealed interface RevealOutcome {
    data class Found(val index: Int, val state: ExplorerWorkspaceViewModel.State) : RevealOutcome
    data object NotFound : RevealOutcome
    data object Abandoned : RevealOutcome
}

/**
 * Waits for [states] to answer [request].
 *
 * The [window] for the row to show up runs from the start and restarts whenever a
 * [RevealWait.Pending] phase ends; while pending, no deadline runs at all. Without
 * `awaitDestinationLoad` nothing is ever pending, so that is one [window] from the start.
 */
internal suspend fun awaitReveal(
    states: Flow<ExplorerWorkspaceViewModel.State?>,
    request: ExplorerWorkspaceViewModel.RevealRequest,
    window: Duration = 2.seconds,
): RevealOutcome = coroutineScope {
    val outcome = CompletableDeferred<RevealOutcome>()
    var deadline: Job? = null
    fun startWindow() {
        deadline = launch {
            delay(window)
            outcome.complete(RevealOutcome.NotFound)
        }
    }

    startWindow()
    launch {
        states
            .catch { e -> log(TAG) { "Error waiting for reveal of ${request.path.path}: $e" } }
            .collect { state ->
                state ?: return@collect
                when (val wait = state.revealWait(request)) {
                    RevealWait.Pending -> {
                        deadline?.cancel()
                        deadline = null
                    }
                    RevealWait.Abandoned -> {
                        outcome.complete(RevealOutcome.Abandoned)
                    }
                    is RevealWait.Resolved -> {
                        if (wait.index != null) {
                            outcome.complete(RevealOutcome.Found(wait.index, state))
                        } else if (deadline == null) {
                            startWindow()
                        }
                    }
                }
            }
        outcome.complete(RevealOutcome.NotFound)
    }

    outcome.await().also { coroutineContext.cancelChildren() }
}

internal suspend fun awaitRevealIndex(
    states: Flow<ExplorerWorkspaceViewModel.State?>,
    request: ExplorerWorkspaceViewModel.RevealRequest,
    window: Duration = 2.seconds,
): Int? = (awaitReveal(states, request, window) as? RevealOutcome.Found)?.index

private val TAG = logTag("Explorer", "Page", "Reveal")
