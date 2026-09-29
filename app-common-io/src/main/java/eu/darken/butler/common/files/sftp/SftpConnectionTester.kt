package eu.darken.butler.common.files.sftp

import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.files.sftp.location.TrustedHostKey
import eu.darken.ssh.HostKey
import eu.darken.ssh.HostKeyPolicy
import eu.darken.ssh.SftpEndpoint
import eu.darken.ssh.SftpFileType
import eu.darken.ssh.SftpSession
import eu.darken.ssh.SshCredentials
import eu.darken.ssh.SshException
import eu.darken.ssh.SshException.Kind
import eu.darken.ssh.use
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton
import eu.darken.ssh.SftpPath as ServerPath

/**
 * Checks what the add/edit form entered before anything is stored: the server's identity, the
 * sign-in, and that the base path is a directory the account can list.
 */
@Singleton
class SftpConnectionTester @Inject constructor(
    private val clientFactory: SftpClientFactory,
) {

    sealed interface Result {
        /** [root] is the base path as the server resolved it, e.g. `/home/darken/media`. */
        data class Success(val root: ServerPath) : Result

        /** No key was pinned (or [HostKeyPolicy.Unknown] was asked for): [presented] awaits confirmation. */
        data class HostKeyUnknown(val presented: HostKey) : Result {
            val presentedKey: TrustedHostKey get() = TrustedHostKey.from(presented)
        }

        data class HostKeyMismatch(val expected: HostKey, val presented: HostKey) : Result {
            val expectedKey: TrustedHostKey get() = TrustedHostKey.from(expected)
            val presentedKey: TrustedHostKey get() = TrustedHostKey.from(presented)
        }

        data object AuthenticationFailed : Result

        data object KeyFormatInvalid : Result

        data object KeyPassphraseInvalid : Result

        data object BasePathMissing : Result

        data object BasePathNotDirectory : Result

        data object BasePathAccessDenied : Result

        data class Unreachable(val cause: Throwable) : Result
    }

    /**
     * Free for everyone, so a user can check their server before buying: it only connects, probes the
     * base path and disconnects.
     *
     * Secrets are passed as for [eu.darken.butler.common.files.sftp.location.SftpLocationManager]:
     * [password] for [SftpLocation.AuthType.PASSWORD], [privateKey] and [passphrase] for
     * [SftpLocation.AuthType.PRIVATE_KEY]. The caller keeps ownership of every array.
     */
    suspend fun test(
        host: String,
        port: Int,
        username: String,
        authType: SftpLocation.AuthType,
        password: CharArray?,
        privateKey: ByteArray?,
        passphrase: CharArray?,
        basePath: String,
        hostKeyPolicy: HostKeyPolicy,
    ): Result {
        val endpoint = "$username@$host:$port"

        val credentials = when (authType) {
            SftpLocation.AuthType.PASSWORD -> SshCredentials.Password(
                username,
                requireNotNull(password) { "A password is required" },
            )

            SftpLocation.AuthType.PRIVATE_KEY -> SshCredentials.PrivateKey(
                username,
                requireNotNull(privateKey) { "A private key is required" },
                passphrase,
            )
        }

        val client = clientFactory.create()
        return try {
            val session = try {
                client.connect(SftpEndpoint(host, port), credentials, hostKeyPolicy)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return connectFailure(error, hostKeyPolicy).also { log(TAG, WARN) { "$endpoint: $it" } }
            }
            session.use { probeRoot(it, basePath) }.also { log(TAG, INFO) { "$endpoint: $it" } }
        } finally {
            runCatching { client.close() }
        }
    }

    /**
     * [test] for callers without the SSH library on their classpath: a null [trustedKey] asks the
     * server for its key ([HostKeyPolicy.Unknown]), any other value pins it.
     */
    suspend fun test(
        host: String,
        port: Int,
        username: String,
        authType: SftpLocation.AuthType,
        password: CharArray?,
        privateKey: ByteArray?,
        passphrase: CharArray?,
        basePath: String,
        trustedKey: TrustedHostKey?,
    ): Result = test(
        host = host,
        port = port,
        username = username,
        authType = authType,
        password = password,
        privateKey = privateKey,
        passphrase = passphrase,
        basePath = basePath,
        hostKeyPolicy = trustedKey?.let { HostKeyPolicy.Pinned(it.toHostKey()) } ?: HostKeyPolicy.Unknown,
    )

    private fun connectFailure(error: Exception, policy: HostKeyPolicy): Result {
        val ssh = error as? SshException ?: return Result.Unreachable(error)
        val presented = ssh.presentedHostKey
        return when (ssh.kind) {
            Kind.HOST_KEY_UNKNOWN -> presented?.let { Result.HostKeyUnknown(it) }
            Kind.HOST_KEY_MISMATCH -> {
                val expected = (policy as? HostKeyPolicy.Pinned)?.expected
                if (presented != null && expected != null) Result.HostKeyMismatch(expected, presented) else null
            }

            Kind.AUTHENTICATION -> Result.AuthenticationFailed
            Kind.KEY_FORMAT -> Result.KeyFormatInvalid
            Kind.KEY_PASSPHRASE -> Result.KeyPassphraseInvalid
            else -> null
        } ?: Result.Unreachable(error)
    }

    private suspend fun probeRoot(session: SftpSession, basePath: String): Result = try {
        val root = SftpRoot.resolve(session, basePath)
        if (session.stat(root).type != SftpFileType.DIRECTORY) {
            Result.BasePathNotDirectory
        } else {
            // Only a listing proves the directory can be browsed, a stat also succeeds without read access.
            session.list(root).firstOrNull()
            Result.Success(root)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log(TAG, WARN) { "Probing base path '$basePath' failed: ${error.asLog()}" }
        when ((error as? SshException)?.kind) {
            Kind.MISSING -> Result.BasePathMissing
            Kind.NOT_DIRECTORY -> Result.BasePathNotDirectory
            Kind.ACCESS_DENIED -> Result.BasePathAccessDenied
            else -> Result.Unreachable(error)
        }
    }

    companion object {
        private val TAG = logTag("SFTP", "ConnectionTester")
    }
}
