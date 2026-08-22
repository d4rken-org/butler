package eu.darken.butler.common.ipc

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

/**
 * Gates a freshly established host connection on the identity the host echoes back, and recovers
 * once if it doesn't match. Shared by [eu.darken.butler.common.root.service.RootServiceClient] and
 * [eu.darken.butler.common.adb.service.AdbServiceClient], whose handshakes only differ in types.
 *
 * A mismatch throws, which cancels the upstream connection flow: that runs the launcher's teardown
 * (root session close / Shizuku unbind plus the bounded await for the actual disconnect) to
 * completion before this operator re-collects, so the reconnect can't race its own predecessor's
 * unbind. Awaiting matters for the Shizuku path in particular, where a late `remove=true` unbind is
 * keyed on the service args and would otherwise remove the newer generation (see AdbHostLauncher).
 *
 * Exactly one retry, no loop: the case worth recovering from is a stale host that goes away when
 * torn down. If the second attempt lands on the same host again (Shizuku handing back a still-running
 * user service), the mismatch is real and belongs to the caller.
 *
 * @param expected our own identity, re-read per attempt
 * @param checkBase the host round-trip whose first line carries the echo
 * @param onAccepted builds the connection handed downstream, given the host and its verified identity
 */
internal fun <IPC : Any, CONNECTION : Any> Flow<IPC>.gateOnHostIdentity(
    tag: String,
    expected: () -> IpcContract.HostIdentity,
    checkBase: (IPC) -> String?,
    onAccepted: (IPC, IpcContract.HostIdentity) -> CONNECTION,
): Flow<CONNECTION> = this
    .map { ipc ->
        val ours = expected()
        val reply = checkBase(ipc)
        val theirs = IpcContract.decode(reply)
        if (theirs != ours) {
            // A host from a different app installation: it runs that installation's code and its AIDL
            // transaction codes need not line up with ours, so refuse before any module client can
            // issue a call against it.
            log(tag, WARN) {
                "Host identity mismatch!\n" +
                    "expected: $ours\n" +
                    "reported: ${theirs ?: "<undecodable> (first line: ${reply?.lineSequence()?.firstOrNull()})"}"
            }
            throw IpcContractMismatchException("Host was launched by a different app installation")
        }
        onAccepted(ipc, ours)
    }
    .retryWhen { cause, attempt ->
        if (cause !is IpcContractMismatchException || attempt > 0) return@retryWhen false
        log(tag, WARN) { "Stale host was torn down, reconnecting once…" }
        true
    }
