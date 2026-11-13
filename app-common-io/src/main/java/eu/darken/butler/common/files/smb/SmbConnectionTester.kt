package eu.darken.butler.common.files.smb

import eu.darken.smb.SmbCredentials
import eu.darken.smb.SmbEndpoint
import eu.darken.smb.SmbServer
import eu.darken.smb.use
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmbConnectionTester @Inject constructor(
    private val clientFactory: SmbClientFactory,
) {
    /**
     * Free for everyone, so a user can check their server before buying: it only connects and
     * disconnects. A null [share] tests a server entry by listing the server's shares, an empty
     * list included.
     */
    suspend fun test(
        host: String,
        port: Int,
        share: String?,
        username: String?,
        domain: String?,
        password: CharArray?,
    ) {
        val credentials = when {
            username.isNullOrEmpty() || password == null -> SmbCredentials.Guest
            else -> SmbCredentials.Password(username, password, domain.orEmpty())
        }
        if (share == null) {
            try {
                clientFactory.createLister().listShares(SmbServer(host, port), credentials)
            } catch (error: Exception) {
                throw SmbStatusMapper.mapListShares(error, "$host:$port")
            }
            return
        }
        try {
            clientFactory.create().connect(SmbEndpoint(host, share, port), credentials).use { }
        } catch (error: Exception) {
            throw SmbStatusMapper.mapConnect(error, "$host:$port/$share", share)
        }
    }
}
