package eu.darken.butler.common.files.smb

import com.hierynomus.mssmb.SMB1NotSupportedException
import com.hierynomus.smbj.SmbConfig
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import java.io.EOFException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells an SMB1-only server apart from an unreachable one.
 *
 * A server that speaks only SMB1 answers Butler's SMB2 negotiate by hanging up, which is
 * indistinguishable from a dead host. Only after such a failure does this open a second, throwaway
 * connection with multi-protocol negotiation, where an SMB1-only server identifies itself. Normal
 * connections never send an SMB1 packet: servers with SMB1 disabled drop those, so making it the
 * default would break the servers Butler actually supports.
 */
@Singleton
class SmbDialectProbe @Inject constructor(
    private val clientFactory: SmbClientFactory,
) {

    /** Whether [error] looks like a negotiate that was hung up on rather than answered. */
    fun isWorthProbing(error: Throwable): Boolean = error.causeChain().any { it is EOFException }

    fun isSmb1Only(host: String, port: Int): Boolean {
        val client = clientFactory.create(PROBE_CONFIG)
        return try {
            client.connect(host, port).close()
            false
        } catch (e: Exception) {
            // smbj wraps the negotiate result, the SMB1 verdict sits somewhere in the chain
            e.causeChain().any { it is SMB1NotSupportedException }
                .also { if (it) log(TAG, INFO) { "$host:$port only offers SMB1" } }
        } finally {
            runCatching { client.close() }
        }
    }

    private fun Throwable.causeChain(): Sequence<Throwable> {
        val seen = mutableSetOf<Throwable>()
        return generateSequence(this) { it.cause }.takeWhile { seen.add(it) }
    }

    companion object {
        private val TAG = logTag("SMB", "DialectProbe")

        private val PROBE_CONFIG: SmbConfig = SmbConfig.builder()
            .withSocketFactory(SmbSocketFactory())
            .withSoTimeout(SmbSocketFactory.SOCKET_TIMEOUT_MS)
            .withMultiProtocolNegotiate(true)
            .withDfsEnabled(false)
            .build()
    }
}
