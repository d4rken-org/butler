package eu.darken.butler.common.ipc

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

/**
 * One host generation on its way into [gateOnHostIdentity].
 *
 * @param ipc the freshly connected host
 * @param disconnectConfirmed this generation's teardown signal, completing with true only if that
 * teardown is known to have finished (see AdbHostLauncher.ConnectionWrapper). Null when the launcher
 * has nothing to confirm because its teardown is fully structured, i.e. finishes before the producer
 * coroutine does — the root path.
 */
internal data class IpcHostAttempt<IPC : Any>(
    val ipc: IPC,
    val disconnectConfirmed: Deferred<Boolean>? = null,
)

/**
 * Gates a freshly established host connection on the identity the host echoes back, and recovers
 * once if it doesn't match. Shared by [eu.darken.butler.common.root.service.RootServiceClient] and
 * [eu.darken.butler.common.adb.service.AdbServiceClient], whose handshakes only differ in types.
 *
 * A mismatch throws, which cancels the upstream connection flow and runs the launcher's teardown
 * before this operator can re-collect. On the root path that settles it: the teardown (IPC receiver
 * release, session close) is structured, so the producer coroutine finishing means the host is gone,
 * and [IpcHostAttempt.disconnectConfirmed] is null.
 *
 * Shizuku is not like that. Its `unbindUserService()` is a synchronous binder transaction that can
 * wedge, so AdbHostLauncher runs it detached under a timeout and gives up waiting when that expires:
 * the producer can finish with the unbind still in flight. Since Shizuku keys a `remove=true` unbind
 * on the service args rather than on our callback, rebinding then risks the late unbind removing the
 * REPLACEMENT — recovery killing what it just recovered. So this reconnects only when the launcher
 * confirms the teardown finished; otherwise the mismatch propagates and the caller sees the host as
 * unavailable, which is the better of the two outcomes.
 *
 * Exactly one retry, no loop: the case worth recovering from is a stale host that goes away when
 * torn down. If the second attempt lands on the same host again (Shizuku handing back a still-running
 * user service), the mismatch is real and belongs to the caller.
 *
 * @param expected our own identity, re-read per attempt
 * @param checkBase the host round-trip whose first line carries the echo
 * @param onAccepted builds the connection handed downstream, given the host and its verified identity
 */
internal fun <IPC : Any, CONNECTION : Any> Flow<IpcHostAttempt<IPC>>.gateOnHostIdentity(
    tag: String,
    expected: () -> IpcContract.HostIdentity,
    checkBase: (IPC) -> String?,
    onAccepted: (IPC, IpcContract.HostIdentity) -> CONNECTION,
): Flow<CONNECTION> = this
    .map { host ->
        val ours = expected()
        val reply = checkBase(host.ipc)
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
            throw IpcContractMismatchException(
                message = "Host was launched by a different app installation",
                disconnectConfirmed = host.disconnectConfirmed,
            )
        }
        onAccepted(host.ipc, ours)
    }
    .retryWhen { cause, attempt ->
        if (cause !is IpcContractMismatchException || attempt > 0) return@retryWhen false
        // The teardown has already run by the time we get here (collection awaits the upstream
        // producer); this asks whether it actually finished. It is that teardown which completes the
        // signal, so awaiting it doesn't add a wait.
        val confirmed = cause.disconnectConfirmed?.await() ?: true
        if (confirmed) {
            log(tag, WARN) { "Stale host was torn down, reconnecting once…" }
        } else {
            log(tag, ERROR) {
                "Stale host teardown was not confirmed, not reconnecting: a replacement bound now " +
                    "could be removed by the unbind that is still in flight."
            }
        }
        confirmed
    }
