package eu.darken.butler.common.ipc

import android.os.DeadObjectException
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import kotlin.coroutines.cancellation.CancellationException

interface IpcClientModule {

    fun Throwable.refineException(): Throwable = when (this) {
        is CancellationException -> this
        is DeadObjectException -> ServiceConnectionLostException(this)
        else -> unwrapPropagation()
    }

    /**
     * Clients route everything they catch through here, including exceptions thrown on this side of
     * the binder (lost connections, oversized transactions, truncated streams). Only the marker
     * says an exception actually came from a host, so anything without it is handed back untouched.
     */
    fun Throwable.unwrapPropagation(): Throwable {
        val carrier = (this as? UnsupportedOperationException)?.message ?: return this

        return IpcErrorCodec.decodeIfMarked(carrier, stackTrace)?.also {
            log(TAG, VERBOSE) { "Propagating unwrapped exception: $it" }
        } ?: this
    }

    /**
     * The stream counterpart: those events carry the host error in a [String] field instead of a
     * binder exception. Null means nothing was encoded, so the caller keeps the raw text.
     */
    fun decodeStreamError(carrier: String?): Throwable? =
        IpcErrorCodec.decodeIfMarked(carrier, Throwable().stackTrace)

    companion object {
        private val TAG = logTag("IPC", "Module")
    }

}
