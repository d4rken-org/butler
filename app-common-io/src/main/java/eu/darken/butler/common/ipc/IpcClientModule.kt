package eu.darken.butler.common.ipc

import android.os.DeadObjectException
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import java.io.ByteArrayInputStream
import java.io.ObjectInputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

interface IpcClientModule {

    @OptIn(ExperimentalEncodingApi::class)
    fun String.decodeStacktrace(): Array<StackTraceElement>? = try {
        val decodedBytes = Base64.decode(this)
        ObjectInputStream(ByteArrayInputStream(decodedBytes)).use {
            @Suppress("UNCHECKED_CAST")
            it.readObject() as Array<StackTraceElement>
        }
    } catch (_: Exception) {
        null
    }

    fun Throwable.refineException(): Throwable = when (this) {
        is CancellationException -> this
        is DeadObjectException -> ServiceConnectionLostException(this)
        else -> unwrapPropagation()
    }

    fun Throwable.unwrapPropagation(): Throwable {
        val matchResult = Regex("^([a-zA-Z0-9$.]+Exception): ").find((message ?: ""))
        val exceptionName = matchResult?.groupValues?.get(1)
        if (exceptionName == null) {
            log(TAG, WARN) { "Couldn't unwrap exception, it didn't match: $this" }
            return this
        }
        val messageParts = message!!
            .removePrefix(matchResult.groupValues.first())
            .split(IpcHostModule.STACK_MARKER)
            .map { it.trim() }

        val unwrappedException = try {
            Class.forName(exceptionName)
                .asSubclass(Throwable::class.java)
                .getConstructor(String::class.java)
                .newInstance(messageParts.first())
                .also { newException ->
                    // Android's Parcel preserves only an exception's message across the binder, not
                    // its stack trace; we decode the trace from the Base64 marker the host appended
                    // to the message (see IpcHostModule).
                    if (Bugs.isDebug && messageParts.size > 1) {
                        log(TAG, VERBOSE) { "Decoding stacktrace..." }
                        messageParts[1].decodeStacktrace()?.let { remoteTrace ->
                            // Stacktrace on this side of the binder + the stacktrace on the other side of it
                            newException.stackTrace = (remoteTrace + stackTrace).filter {
                                !it.className.startsWith("android.os.Binder") && !it.className.startsWith("android.os.Parcel")
                            }.toTypedArray()
                        }
                    }
                }
        } catch (e: Exception) {
            log(TAG, WARN) { "Failed to unwrap exception:\n---\n$this\n---\n${e.asLog()}" }
            UnwrappedIPCException(this.toString())
        }

        log(TAG, VERBOSE) { "Propagating unwrapped exception: $unwrappedException" }
        return unwrappedException
    }

    companion object {
        private val TAG = logTag("IPC", "Module")
    }

}
