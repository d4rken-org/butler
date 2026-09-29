package eu.darken.butler.explorer.core

import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.extensions.isFile
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * Reads a private key file the user picked for an SFTP server into memory.
 *
 * The cap is enforced on the stream, not on a reported size: a provider may report none or a wrong
 * one. Key files are a few KiB, anything past [MAX_BYTES] is not a key.
 */
class SftpPrivateKeyReader @Inject constructor(
    private val gatewaySwitch: GatewaySwitch,
) {

    sealed interface Result {
        /** [bytes] belongs to the caller, who wipes it once the key is no longer needed. */
        class Loaded(val name: String, val bytes: ByteArray) : Result {
            override fun toString(): String = "Loaded($name, <redacted>)"
        }

        data object TooLarge : Result

        data object NotAFile : Result

        data class Failed(val error: Throwable) : Result
    }

    suspend fun read(path: APath<*>): Result = try {
        gatewaySwitch.useRes { readCapped(path) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(TAG, WARN) { "read($path) failed: ${e.asLog()}" }
        Result.Failed(e)
    }

    private suspend fun readCapped(path: APath<*>): Result {
        if (!gatewaySwitch.lookup(path, LookupOptions()).isFile) return Result.NotAFile

        val buffer = ByteArray(MAX_BYTES + 1)
        try {
            var total = 0
            gatewaySwitch.openInputStream(path).use { input ->
                while (total < buffer.size) {
                    val read = input.read(buffer, total, buffer.size - total)
                    if (read == -1) break
                    total += read
                }
            }
            if (total > MAX_BYTES) return Result.TooLarge
            return Result.Loaded(path.name, buffer.copyOf(total))
        } finally {
            buffer.fill(0)
        }
    }

    companion object {
        const val MAX_BYTES = 64 * 1024
        private val TAG = logTag("Explorer", "SFTP", "KeyReader")
    }
}
