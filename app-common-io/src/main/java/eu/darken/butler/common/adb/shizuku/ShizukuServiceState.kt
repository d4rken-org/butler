package eu.darken.butler.common.adb.shizuku

/**
 * Why our privileged Shizuku service is (not) usable.
 *
 * Exists because a bare Boolean cannot tell "still nothing yet" apart from "we tried and it failed",
 * which is what left the setup card showing the same waiting message forever on devices where
 * Shizuku's user service never comes up.
 */
sealed interface ShizukuServiceState {

    /** Whether this is an answer worth telling the user about, and worth offering a retry for. */
    val isTerminalFailure: Boolean
        get() = false

    /** Nothing has probed yet. */
    data object NotChecked : ShizukuServiceState

    /** Our service is up and answering. */
    data object Available : ShizukuServiceState

    /** Shizuku says we do not have permission. */
    data object PermissionDenied : ShizukuServiceState

    /**
     * The grant state could not be read. NOT the same as [PermissionDenied]: it means "cannot know".
     *
     * Deliberately not a terminal failure even though [ShizukuWrapper.isGranted] also returns null
     * when its own watchdog expires, so a wedged Shizuku server lands here too. The overwhelmingly
     * common cause is simply that Shizuku has not been started yet, and telling that user their setup
     * failed would be wrong.
     */
    data object Unknown : ShizukuServiceState

    /**
     * A step of the connect sequence spent its whole budget without answering.
     *
     * The signature of Shizuku's user service never calling back. Terminal: repeating the attempt
     * right away cannot fail differently, it just spends the budget again.
     */
    data object TimedOut : ShizukuServiceState {
        override val isTerminalFailure: Boolean = true
    }

    /**
     * We reached Shizuku and it did not give us a usable service.
     *
     * Terminal as well, because the same upstream defect behind [TimedOut] also surfaces as a
     * handshake failure rather than a timeout, and leaving that unclassified is how it ends up
     * rendered as "still waiting" forever.
     *
     * Carries no [Throwable] on purpose: it gets cached in long-lived UI state, the manager already
     * logs the exception, and nothing downstream reads it.
     */
    data object Failed : ShizukuServiceState {
        override val isTerminalFailure: Boolean = true
    }
}
