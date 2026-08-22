package eu.darken.butler.common.ipc

import java.util.concurrent.atomic.AtomicReference

/**
 * HOST-side holder for the encoded [IpcContract.HostIdentity] the launching client stamped into this
 * process, echoed back as the first line of `checkBase()`.
 *
 * The first non-null stamp wins and nothing can change it afterwards. That is what makes it a launch
 * stamp: a newer client that binds to a host left over from an older installation (Shizuku hands back
 * the still-running user service and pushes its options into it) must not be able to overwrite the
 * older identity, or the mismatch it is meant to reveal would disappear. Hosts old enough to have no
 * stamping code at all cannot be re-stamped either — they answer with their own older marker.
 */
class IpcHostIdentityStamp {

    private val stamped = AtomicReference<String?>(null)

    fun stamp(encodedIdentity: String?) {
        if (encodedIdentity != null) stamped.compareAndSet(null, encodedIdentity)
    }

    /** First line of the `checkBase()` reply. [IpcContract.UNSTAMPED] decodes to null, i.e. mismatch. */
    fun asReplyLine(): String = stamped.get() ?: IpcContract.UNSTAMPED
}
