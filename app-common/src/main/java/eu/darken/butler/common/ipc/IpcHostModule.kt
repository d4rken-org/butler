package eu.darken.butler.common.ipc

import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

interface IpcHostModule {

    @OptIn(ExperimentalEncodingApi::class)
    fun Array<StackTraceElement>.encodeBase64(): String? = try {
        val baos = ByteArrayOutputStream()
        ObjectOutputStream(baos).use {
            it.writeObject(this)
        }
        Base64.encode(baos.toByteArray())
    } catch (e: Exception) {
        null
    }


    // Not all exception can be passed through the binder
    // See Parcel.writeException(...)
    fun Throwable.wrapToPropagate(): Exception {
        val msgBuilder = StringBuilder()
        msgBuilder.append("${this.javaClass.name}: $message")
        cause?.let {
            msgBuilder.append("\nCaused by: ")
            msgBuilder.append(it.toString())
        }

        if (Bugs.isDebug) {
            log(VERBOSE) { "Encoding stacktrace..." }
            // Parcel can't carry the stack trace across the binder, so we append it Base64-encoded
            // to the exception message; IpcClientModule decodes it on the other side.
            val encodedTrace = stackTrace.encodeBase64()
            if (encodedTrace != null) {
                msgBuilder
                    .append("\n\n")
                    .append(STACK_MARKER)
                    .append(encodedTrace)
            }
        }

        return UnsupportedOperationException(msgBuilder.toString())
    }

    companion object {
        const val STACK_MARKER = "#STACK#:"
    }
}