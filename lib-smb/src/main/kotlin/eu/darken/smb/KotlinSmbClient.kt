package eu.darken.smb

import eu.darken.smb.protocol.Connection
import eu.darken.smb.protocol.NativeShare
import eu.darken.smb.protocol.isSmb1Only

public class KotlinSmbClient(config: SmbConfig = SmbConfig()) : SmbConnector {
    private val config = config.copy(dialects = config.dialects.toSet())

    override suspend fun connect(endpoint: SmbEndpoint, credentials: SmbCredentials): SmbShare {
        val connection = Connection(endpoint, config)
        return try {
            connection.connect(credentials)
            NativeShare(connection)
        } catch (error: Throwable) {
            connection.disconnect()
            val seen = mutableSetOf<Throwable>()
            val eof =
                generateSequence(error) { it.cause }
                    .takeWhile { seen.add(it) }
                    .any { it is java.io.EOFException }
            if (!connection.negotiated && eof && isSmb1Only(endpoint, config)) {
                throw SmbException(SmbException.Kind.UNSUPPORTED_DIALECT, cause = error)
            }
            throw error
        }
    }
}
