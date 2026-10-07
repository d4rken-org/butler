package eu.darken.butler.common.files.sftp

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import eu.darken.butler.common.coroutine.DefaultDispatcherProvider
import eu.darken.butler.common.files.SftpPath
import eu.darken.butler.common.files.sftp.credentials.KeystoreSftpCredentialCipher
import eu.darken.butler.common.files.sftp.credentials.SftpCredentialStore
import eu.darken.butler.common.files.sftp.credentials.db.SftpCredentialDatabase
import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.files.sftp.location.SftpLocationManagerImpl
import eu.darken.butler.common.files.sftp.location.TrustedHostKey
import eu.darken.butler.common.files.sftp.location.db.SftpLocationDatabase
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.ssh.HostKey
import eu.darken.ssh.SftpEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assume.assumeTrue
import java.util.Base64
import kotlin.time.Instant

/**
 * The SFTP test server (tools/sftp-test-server.sh) as the `sftpHost`/`sftpPort` instrumentation
 * arguments describe it, see tools/sftp-device-test.sh. Skips the calling test when they are absent.
 */
internal fun sftpTestEndpoint(): SftpEndpoint {
    val args = InstrumentationRegistry.getArguments()
    val host = args.getString("sftpHost")
    val port = args.getString("sftpPort")
    assumeTrue("sftpHost/sftpPort not set, see tools/sftp-device-test.sh", host != null && port != null)
    return SftpEndpoint(host!!, port!!.toInt())
}

/** A file from lib-ssh's test keys, bundled as androidTest assets. */
internal fun sftpTestKey(name: String): ByteArray =
    InstrumentationRegistry.getInstrumentation().context.assets.open("keys/$name").use { it.readBytes() }

/** Parses an OpenSSH `.pub` line such as `ssh-ed25519 AAAA... comment`. */
internal fun sftpTestPublicKey(pubName: String): HostKey {
    val (type, blob) = sftpTestKey(pubName).decodeToString().trim().split(' ')
    return HostKey(type, Base64.getDecoder().decode(blob))
}

/**
 * The app-side SFTP stack as Hilt wires it, over in-memory databases: location manager, credential
 * store with the real AndroidKeyStore cipher, connection pool and gateway.
 */
internal class SftpDeviceRig(private val endpoint: SftpEndpoint) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dispatchers = DefaultDispatcherProvider()

    private val locationDb = Room.inMemoryDatabaseBuilder(context, SftpLocationDatabase::class.java).build()
    private val credentialDb = Room.inMemoryDatabaseBuilder(context, SftpCredentialDatabase::class.java).build()

    private val credentialStore = SftpCredentialStore(credentialDb.sftpCredentials(), KeystoreSftpCredentialCipher())
    val locations = SftpLocationManagerImpl(scope, locationDb.sftpLocations(), credentialStore)

    val pool = SftpConnectionPool(
        appScope = scope,
        locationManager = locations,
        credentialStore = credentialStore,
        clientFactory = SftpClientFactoryModule.clientFactory(),
        upgradeRepo = ProUpgradeRepo,
    )
    val gateway = SftpGateway(scope, dispatchers, SftpFileSystemOps(pool, dispatchers), pool)

    /** A remembered password location for `butler`, whose credential goes through the Keystore. */
    suspend fun passwordLocation(
        basePath: String = "/upload",
        hostKey: HostKey = sftpTestPublicKey("host_ed25519.pub"),
    ): SftpPath {
        val location = locations.create(
            label = null,
            host = endpoint.host,
            port = endpoint.port,
            username = PASSWORD_USER,
            basePath = basePath,
            authType = SftpLocation.AuthType.PASSWORD,
            rememberCredential = true,
            password = PASSWORD.toCharArray(),
            privateKey = null,
            passphrase = null,
            hostKey = TrustedHostKey.from(hostKey),
        )
        return SftpPath.root(location.id)
    }

    /** A remembered location for `keyuser`, signing in with the encrypted ed25519 key. */
    suspend fun keyLocation(basePath: String = "/upload"): SftpPath {
        val location = locations.create(
            label = null,
            host = endpoint.host,
            port = endpoint.port,
            username = KEY_USER,
            basePath = basePath,
            authType = SftpLocation.AuthType.PRIVATE_KEY,
            rememberCredential = true,
            password = null,
            privateKey = sftpTestKey("user_ed25519"),
            passphrase = ED25519_PASSPHRASE.toCharArray(),
            hostKey = TrustedHostKey.from(sftpTestPublicKey("host_ed25519.pub")),
        )
        return SftpPath.root(location.id)
    }

    suspend fun close() {
        pool.close()
        scope.cancel()
        locationDb.close()
        credentialDb.close()
    }

    private object ProUpgradeRepo : UpgradeRepo {
        private object Info : UpgradeRepo.Info {
            override val type = UpgradeRepo.Type.FOSS
            override val isPro = true
            override val isSettled = true
            override val upgradedAt: Instant? = null
            override val error: Throwable? = null
        }

        override val storeSite = ""
        override val upgradeSite = ""
        override val betaSite = ""
        override val upgradeInfo: Flow<UpgradeRepo.Info> = MutableStateFlow(Info)

        override suspend fun refresh() = Unit
    }

    companion object {
        const val PASSWORD_USER = "butler"
        const val PASSWORD = "butlerpass"
        const val KEY_USER = "keyuser"
        const val ED25519_PASSPHRASE = "ed25519-passphrase"
    }
}
