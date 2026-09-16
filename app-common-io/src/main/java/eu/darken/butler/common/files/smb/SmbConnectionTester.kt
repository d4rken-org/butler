package eu.darken.butler.common.files.smb

import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.upgrade.isProForUi
import eu.darken.smb.SmbCredentials
import eu.darken.smb.SmbEndpoint
import eu.darken.smb.use
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmbConnectionTester @Inject constructor(
    private val clientFactory: SmbClientFactory,
    private val upgradeRepo: UpgradeRepo,
) {
    /** Builds its own client instead of leasing one, so [SmbConnectionPool]'s gate does not cover it. */
    suspend fun test(
        host: String,
        port: Int,
        share: String,
        username: String?,
        domain: String?,
        password: CharArray?,
    ) {
        if (!upgradeRepo.isProForUi()) throw SmbProRequiredException()
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
