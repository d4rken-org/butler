package eu.darken.butler.common.pkgs.installer

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lets one install at a time use the platform installer.
 *
 * Android shows a single install confirmation, and the answer to it lands on the session that asked
 * for it first. A second session committed while an earlier confirmation is still unanswered has
 * that answer applied to the earlier session - installing the wrong package - while the second one
 * never hears anything and waits out its own timeout.
 */
@Singleton
class SystemInstallGate @Inject constructor() {

    private val lock = Any()
    private var holder: Claim? = null

    /** Takes the installer for [label], or names who is holding it right now. */
    fun claim(label: String): Outcome = synchronized(lock) {
        holder?.let { return Outcome.Busy(it.label) }
        Claim(label).also { holder = it }.let { Outcome.Granted(it) }
    }

    /** Hands the installer back. A claim that is no longer the current one changes nothing. */
    fun release(claim: Claim) = synchronized(lock) {
        if (holder === claim) holder = null
    }

    /** What one install holds the platform installer with. Identity is what [release] goes by. */
    class Claim(val label: String)

    sealed interface Outcome {
        data class Granted(val claim: Claim) : Outcome

        /** [label] is what the install already using the installer is called. */
        data class Busy(val label: String) : Outcome
    }
}
