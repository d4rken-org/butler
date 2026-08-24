package eu.darken.butler.common.files.smb

import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verifies a set of connection details before anything is stored, so a typo or a wrong password is
 * reported in the form instead of turning into a broken location the user has to debug later.
 */
@Singleton
class SmbConnectionTester @Inject constructor(
    private val clientFactory: SmbClientFactory,
    private val dispatcherProvider: DispatcherProvider,
) {

    /**
     * @throws SmbUnreachableException, SmbAuthException, SmbShareNotFoundException,
     * SmbDialectNotSupportedException
     */
    suspend fun test(
        host: String,
        port: Int,
        share: String,
        username: String?,
        domain: String?,
        password: CharArray?,
    ) = withContext(dispatcherProvider.IO) {
        val endpoint = "$host:$port/$share"
        log(TAG) { "test($endpoint, username=$username)" }

        val authContext = when {
            username.isNullOrEmpty() || password == null -> AuthenticationContext.guest()
            else -> AuthenticationContext(username, password, domain)
        }

        val client = clientFactory.create(SmbConnectionPool.CONFIG)
        try {
            client.connect(host, port).use { connection ->
                connection.authenticate(authContext).use { session ->
                    val connected = session.connectShare(share)
                    if (connected !is DiskShare) throw SmbShareNotFoundException(endpoint, share)
                    connected.close()
                }
            }
            log(TAG, INFO) { "test($endpoint): OK" }
        } catch (e: Exception) {
            log(TAG, WARN) { "test($endpoint) failed: ${e.asLog()}" }
            throw SmbStatusMapper.mapConnect(e, endpoint, share)
        } finally {
            runCatching { client.close() }
        }
    }

    companion object {
        private val TAG = logTag("SMB", "ConnectionTester")
    }
}
