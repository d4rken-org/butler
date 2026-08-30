package eu.darken.butler.setup.core.root

/**
 * Why our privileged root service is (not) usable.
 *
 * Exists because a bare Boolean cannot tell "still connecting" apart from "we tried and there is no
 * service", and acquiring the root host can cold-bind an su session, so the setup card sits in that
 * window long enough for the difference to be visible.
 */
sealed interface RootServiceState {

    /** Root is not enabled, nothing has probed. */
    data object NotChecked : RootServiceState

    /** The binder has not resolved yet. */
    data object Connecting : RootServiceState

    /** Our service is up and answering with the identity the connection was gated on. */
    data object Available : RootServiceState

    /**
     * The binder resolved to nothing, the probe threw, or the reply came from another installation.
     *
     * Carries no [Throwable] on purpose: it gets cached in long-lived UI state, the module already
     * logs the exception, and nothing downstream reads it.
     */
    data object Failed : RootServiceState
}
