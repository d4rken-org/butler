package eu.darken.butler.common.files.smb

import eu.darken.smb.SmbCredentials
import eu.darken.smb.SmbEndpoint
import eu.darken.smb.use
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmbConnectionTester @Inject constructor(
    private val clientFactory: SmbClientFactory,
) {
    suspend fun test(
        host: String,
        port: Int,
        share: String,
        username: String?,
        domain: String?,
        password: CharArray?,
    ) {
        val credentials = when {
            username.isNullOrEmpty() || password == null -> SmbCredentials.Guest
            else -> SmbCredentials.Password(username, password, domain.orEmpty())
        }
        try {
            clientFactory.create().connect(SmbEndpoint(host, share, port), credentials).use { }
        } catch (error: Exception) {
            throw SmbStatusMapper.mapConnect(error, "$host:$port/$share", share)
        }
    }
}
