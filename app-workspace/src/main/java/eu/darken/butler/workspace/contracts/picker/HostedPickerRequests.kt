package eu.darken.butler.workspace.contracts.picker

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.navigation.NavigationDestination
import kotlinx.serialization.Serializable

/**
 * Butler's own folder picker for screens that are not workspaces, such as settings: it picks one
 * folder and is shown by its own navigation destination ([Ticket.destination]) instead of a tab.
 *
 * ```
 * val ticket = hostedPickers.register(Request(startPath = current))
 * navTo(ticket.destination)
 * when (val outcome = hostedPickers.awaitOutcome(ticket)) {
 *     is Outcome.Picked -> use(outcome.paths.single())
 *     Outcome.Cancelled -> Unit
 * }
 * ```
 *
 * Cancelling the coroutine waiting in [awaitOutcome] abandons the request and closes its picker.
 * Nothing survives process death: a restored destination finds no request and dismisses itself.
 */
interface HostedPickerRequests {

    fun register(request: Request): Ticket

    /**
     * Suspends until the picker of [ticket] is gone and returns how it ended. A request whose picker
     * is already gone returns its outcome immediately; one this process never registered returns
     * [Outcome.Cancelled].
     */
    suspend fun awaitOutcome(ticket: Ticket): Outcome

    data class Request(
        val startPath: APath<*>? = null,
    )

    data class Ticket(val token: String) {
        val destination: NavigationDestination get() = DestinationHostedPicker(token)
    }

    sealed interface Outcome {
        data class Picked(val paths: List<APath<*>>) : Outcome
        data object Cancelled : Outcome
    }
}

@Serializable
data class DestinationHostedPicker(val token: String) : NavigationDestination
