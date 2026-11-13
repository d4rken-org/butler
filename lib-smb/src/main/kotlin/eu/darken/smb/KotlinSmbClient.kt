package eu.darken.smb

import eu.darken.smb.protocol.Connection
import eu.darken.smb.protocol.NamedPipe
import eu.darken.smb.protocol.NativeShare
import eu.darken.smb.protocol.ShareEnumerator
import eu.darken.smb.protocol.TreeTarget
import eu.darken.smb.protocol.isSmb1Only
import kotlinx.coroutines.withTimeoutOrNull

public class KotlinSmbClient(config: SmbConfig = SmbConfig()) : SmbConnector, SmbShareLister {
    private val config = config.copy(dialects = config.dialects.toSet())

    override suspend fun connect(endpoint: SmbEndpoint, credentials: SmbCredentials): SmbShare {
        val connection = Connection(endpoint, config)
        return try {
            connection.connect(credentials)
            NativeShare(connection)
        } catch (error: Throwable) {
            throw connectFailure(connection, endpoint.host, endpoint.port, error)
        }
    }

    /** Opens a dedicated connection to IPC$, bounded by twice the request timeout overall. */
    override suspend fun listShares(
        server: SmbServer,
        credentials: SmbCredentials,
    ): List<SmbShareInfo> {
        val connection = Connection(server.host, server.port, config, TreeTarget.Ipc)
        try {
            return withTimeoutOrNull(config.requestTimeout * 2) {
                try {
                    connection.connect(credentials)
                } catch (error: Throwable) {
                    throw connectFailure(connection, server.host, server.port, error)
                }
                val pipe = NamedPipe.open(connection, "srvsvc")
                var failure: Throwable? = null
                try {
                    ShareEnumerator(pipe, server.host, pipe.maxChunk).list()
                } catch (error: Throwable) {
                    failure = error
                    throw error
                } finally {
                    try {
                        pipe.close()
                    } catch (error: Throwable) {
                        if (failure == null) throw error
                        if (failure !== error) failure.addSuppressed(error)
                    }
                }
            }
                ?: throw SmbException(
                    SmbException.Kind.TRANSPORT,
                    message = "SMB share listing timed out",
                )
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun connectFailure(
        connection: Connection,
        host: String,
        port: Int,
        error: Throwable,
    ): Throwable {
        connection.disconnect()
        val seen = mutableSetOf<Throwable>()
        val eof =
            generateSequence(error) { it.cause }
                .takeWhile { seen.add(it) }
                .any { it is java.io.EOFException }
        if (!connection.negotiated && eof && isSmb1Only(host, port, config)) {
            return SmbException(SmbException.Kind.UNSUPPORTED_DIALECT, cause = error)
        }
        return error
    }
}
