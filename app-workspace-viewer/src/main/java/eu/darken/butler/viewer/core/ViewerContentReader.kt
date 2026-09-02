package eu.darken.butler.viewer.core

import android.content.Context
import android.os.ParcelFileDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.coroutine.openForHandover
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads a [ViewerSource]'s bytes, whichever kind it is.
 *
 * [readInput] is scoped rather than returning a stream on purpose: a gateway stream holds a lease
 * that is only valid inside `useRes`, so handing the stream back to the caller would release the
 * lease before the first byte is read. Callers that need several passes call this several times -
 * gateway streams do not rewind, and the provider gives a fresh one per open too.
 */
@Singleton
class ViewerContentReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
    private val gatewaySwitch: GatewaySwitch,
) {

    /** Runs [block] on a fresh stream for [source], holding the gateway lease for its duration. */
    suspend fun <R> readInput(source: ViewerSource, block: suspend (InputStream) -> R): R = when (source) {
        is ViewerSource.Stored -> gatewaySwitch.useRes {
            gatewaySwitch.openInputStream(source.path).use { block(it) }
        }

        is ViewerSource.Streamed -> withContext(dispatcherProvider.IO) {
            val stream = context.contentResolver.openInputStream(source.uri)
                ?: throw ViewerContentUnreadableException(source.displayName)
            stream.use { block(it) }
        }
    }

    /**
     * A stream whose ownership transfers to the caller, for the one consumer that cannot work
     * inside a scope: telephoto reads its source lazily, long after any block here would return.
     *
     * Deliberately does NOT take a gateway lease. A lease is only valid inside `useRes`, and there
     * is no `useRes` that could span telephoto's lifetime, so pretending to hold one would be worse
     * than not holding one. Prefer [readInput] everywhere else.
     */
    suspend fun openStreamForHandover(source: ViewerSource): InputStream = when (source) {
        is ViewerSource.Stored -> gatewaySwitch.openInputStream(source.path)

        // A cancellation racing the open would otherwise discard the only reference to an open
        // stream. Enough of those and the process runs out of descriptors.
        is ViewerSource.Streamed -> openForHandover(dispatcherProvider.IO) {
            context.contentResolver.openInputStream(source.uri)
        } ?: throw ViewerContentUnreadableException(source.displayName)
    }

    /**
     * A seekable read-only descriptor, or null when there is none. Ownership transfers to the
     * caller, matching what the PDF renderer and the decode check already expect.
     *
     * Null for a non-seekable provider (a pipe): `PdfRenderer` and `BitmapRegionDecoder` both need
     * to seek, and a pipe silently produces a blank render rather than an error.
     */
    suspend fun openReadPfd(source: ViewerSource): ParcelFileDescriptor? = when (source) {
        is ViewerSource.Stored -> gatewaySwitch.openReadPFD(source.path)

        // Same handover reasoning as openStreamForHandover: the descriptor must not be opened and
        // then dropped by a cancellation racing the return.
        is ViewerSource.Streamed -> openForHandover(dispatcherProvider.IO) {
            try {
                context.contentResolver.openFileDescriptor(source.uri, "r")?.seekableOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                // Deliberately NOT swallowed: "the grant lapsed" is a different sentence from "this
                // provider cannot seek", and callers that can tell the user so need to see it.
                throw e
            } catch (e: Exception) {
                log(TAG, WARN) { "openReadPfd(${source.uri}) failed: ${e.asLog()}" }
                null
            }
        }
    }

    /**
     * The backing file when the source is one Butler can read directly, else null. Preserves the
     * existing fast paths that hand a real file to a decoder instead of a stream.
     *
     * A [LocalPath] alone does not imply direct access - LocalGateway auto-escalates to ROOT/ADB for
     * files the app itself cannot open - so readability is checked, not assumed.
     */
    fun localFileOrNull(source: ViewerSource): File? = when (source) {
        is ViewerSource.Stored -> (source.path as? LocalPath)?.file?.takeIf { it.canRead() }
        is ViewerSource.Streamed -> null
    }

    private fun ParcelFileDescriptor.seekableOrNull(): ParcelFileDescriptor? =
        if (statSize >= 0) this else this.also { runCatching { it.close() } }.let { null }

    companion object {
        private val TAG = logTag("Viewer", "ContentReader")
    }
}
