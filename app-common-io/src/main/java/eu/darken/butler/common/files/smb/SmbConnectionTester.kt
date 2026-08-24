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
    private val dialectProbe: SmbDialectProbe,
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

        // The context keeps its own copy of the password; the array itself belongs to the caller.
        var authContext: AuthenticationContext? = when {
            username.isNullOrEmpty() || password == null -> AuthenticationContext.guest()
            else -> AuthenticationContext(username, password, domain)
        }

        val client = clientFactory.create(SmbConnectionPool.CONFIG)
        try {
            client.connect(host, port).use { connection ->
                // Which phase failed decides what "access denied" means, see SmbStatusMapper
                val session = try {
                    connection.authenticate(authContext!!)
                } catch (e: Exception) {
                    throw SmbStatusMapper.mapAuthenticate(e, endpoint)
                }
                session.use {
                    val connected = try {
                        it.connectShare(share)
                    } catch (e: Exception) {
                        throw SmbStatusMapper.mapConnectShare(e, endpoint, share)
                    }
                    if (connected !is DiskShare) throw SmbShareNotFoundException(endpoint, share)
                    connected.close()
                }
            }
            log(TAG, INFO) { "test($endpoint): OK" }
        } catch (e: Exception) {
            log(TAG, WARN) { "test($endpoint) failed: ${e.asLog()}" }
            val mapped = SmbStatusMapper.mapConnect(e, endpoint, share)
            // Same reasoning as in the pool: an SMB1-only server just hangs up on us.
            throw when {
                mapped is SmbUnreachableException &&
                    dialectProbe.isWorthProbing(e) &&
                    dialectProbe.isSmb1Only(host, port) -> SmbDialectNotSupportedException(endpoint, e)

                else -> mapped
            }
        } finally {
            // Nothing past the session setup needs the password, don't hold it any longer
            authContext = null
            runCatching { client.close() }
        }
    }

    companion object {
        private val TAG = logTag("SMB", "ConnectionTester")
    }
}
