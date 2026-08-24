package eu.darken.butler.common.files.smb

import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.extensions.Segments
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.smb.credentials.SmbCredentialCipher
import eu.darken.butler.common.files.smb.credentials.SmbCredentialStore
import eu.darken.butler.common.files.smb.credentials.db.SmbCredentialEntity
import eu.darken.butler.common.files.smb.credentials.db.SmbCredentialsDao
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.common.files.smb.location.SmbLocationManager
import eu.darken.butler.common.sharedresource.useRes
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.buffer
import okio.sink
import okio.source
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Drives the real gateway against a real Samba server.
 *
 * Skipped wherever Docker is not available (most developer machines and every CI job that does not
 * opt in), so it never turns into a flaky gate: the value is in being runnable on demand, the unit
 * tests carry the everyday coverage.
 */
class SmbGatewayIntegrationTest : BaseTest() {

    private val locationId = Uuid.parse("11111111-2222-3333-4444-555555555555")

    private class FakeLocationManager(val location: MutableStateFlow<SmbLocation>) : SmbLocationManager {
        override val locations: Flow<List<SmbLocation>> get() = flowOf(listOf(location.value))
        override suspend fun get(id: Uuid): SmbLocation? = location.value.takeIf { it.id == id }
        override suspend fun create(
            label: String?,
            host: String,
            port: Int,
            share: String,
            basePath: Segments,
            domain: String?,
            username: String?,
            authType: SmbLocation.AuthType,
            rememberCredential: Boolean,
            password: CharArray?,
        ): SmbLocation = throw UnsupportedOperationException()

        override suspend fun update(
            id: Uuid,
            label: String?,
            host: String,
            port: Int,
            share: String,
            basePath: Segments,
            domain: String?,
            username: String?,
            authType: SmbLocation.AuthType,
            rememberCredential: Boolean,
            password: CharArray?,
        ): SmbLocation = throw UnsupportedOperationException()

        override suspend fun delete(id: Uuid) = throw UnsupportedOperationException()
        override suspend fun setLabel(id: Uuid, label: String?) = throw UnsupportedOperationException()
    }

    private class InMemoryCredentialsDao : SmbCredentialsDao {
        val rows = MutableStateFlow<List<SmbCredentialEntity>>(emptyList())
        override fun getAll(): Flow<List<SmbCredentialEntity>> = rows
        override suspend fun getAllOnce() = rows.value
        override suspend fun get(locationId: Uuid, credentialVersion: Int) = rows.value.firstOrNull {
            it.locationId == locationId && it.credentialVersion == credentialVersion
        }

        override suspend fun upsert(entity: SmbCredentialEntity) {
            rows.value = rows.value.filterNot {
                it.locationId == entity.locationId && it.credentialVersion == entity.credentialVersion
            } + entity
        }

        override suspend fun delete(locationId: Uuid) {
            rows.value = rows.value.filterNot { it.locationId == locationId }
        }

        override suspend fun deleteGeneration(locationId: Uuid, credentialVersion: Int) {
            rows.value = rows.value.filterNot {
                it.locationId == locationId && it.credentialVersion == credentialVersion
            }
        }

        override suspend fun deleteOtherGenerations(locationId: Uuid, keepVersion: Int) {
            rows.value = rows.value.filterNot {
                it.locationId == locationId && it.credentialVersion != keepVersion
            }
        }
    }

    private object PlainCipher : SmbCredentialCipher {
        override fun encrypt(locationId: Uuid, payloadVersion: Int, plaintext: ByteArray) =
            SmbCredentialCipher.Envelope(SmbCredentialCipher.ENVELOPE_VERSION, "fake", ByteArray(0), plaintext.copyOf())

        override fun decrypt(locationId: Uuid, payloadVersion: Int, envelope: SmbCredentialCipher.Envelope) =
            envelope.ciphertext

        override fun isKeyAvailable(keyAlias: String) = true
    }

    private data class Rig(
        val ops: SmbFileSystemOps,
        val pool: SmbConnectionPool,
        val credentialStore: SmbCredentialStore,
        val location: MutableStateFlow<SmbLocation>,
    )

    private suspend fun rig(
        container: GenericContainer<*>,
        share: String = SHARE_NAME,
        authType: SmbLocation.AuthType = SmbLocation.AuthType.PASSWORD,
    ): Rig {
        val location = MutableStateFlow(
            SmbLocation(
                id = locationId,
                label = "container",
                host = container.host,
                port = container.getMappedPort(SMB_PORT),
                share = share,
                authType = authType,
                rememberCredential = false,
                credentialVersion = 1,
                username = USERNAME,
                createdAt = Instant.fromEpochMilliseconds(0),
                updatedAt = Instant.fromEpochMilliseconds(0),
            )
        )
        val credentialStore = SmbCredentialStore(InMemoryCredentialsDao(), PlainCipher)
        if (authType == SmbLocation.AuthType.PASSWORD) {
            credentialStore.store(locationId, 1, USERNAME, null, PASSWORD.toCharArray(), remember = false)
        }
        val clientFactory = SmbClientFactory { com.hierynomus.smbj.SMBClient(it) }
        val pool = SmbConnectionPool(
            appScope = TestScope(),
            locationManager = FakeLocationManager(location),
            credentialStore = credentialStore,
            clientFactory = clientFactory,
            dialectProbe = SmbDialectProbe(clientFactory),
        )
        return Rig(SmbFileSystemOps(pool, TestDispatcherProvider()), pool, credentialStore, location)
    }

    private fun path(vararg segments: String) = SmbPath(locationId, segments.toList())

    @Test
    fun `full lifecycle against a real server`() = runTest {
        assumeTrue(dockerAvailable)
        val rig = rig(sambaContainer)
        val ops = rig.ops

        val dir = path("lifecycle")
        ops.createDir(dir)
        ops.exists(dir) shouldBe true
        ops.lookup(dir, LookupOptions()).fileType shouldBe FileType.DIRECTORY

        val file = dir.child("hello.txt")
        ops.openOutputStream(file).sink().buffer().use { it.writeUtf8("hello smb") }

        ops.openInputStream(file).source().buffer().use { it.readUtf8() } shouldBe "hello smb"
        ops.lookup(file, LookupOptions()).size shouldBe "hello smb".length.toLong()

        val listing = ops.lookupFiles(dir, LookupOptions())
        listing.map { it.name } shouldBe listOf("hello.txt")

        val renamed = dir.child("renamed.txt")
        ops.move(file, renamed) shouldBe MoveOutcome.Moved
        ops.exists(file) shouldBe false
        ops.exists(renamed) shouldBe true

        ops.delete(renamed) shouldBe true
        ops.delete(dir, recursive = true) shouldBe true
        ops.exists(dir) shouldBe false

        rig.pool.close()
    }

    @Test
    fun `positioned reads and writes address offsets beyond two gigabytes`() = runTest {
        assumeTrue(dockerAvailable)
        val rig = rig(sambaContainer)
        val ops = rig.ops

        val file = path("sparse.bin")
        ops.createFile(file)

        val payload = "beyond-2GiB".toByteArray()
        ops.file(file, readWrite = true).use { handle ->
            handle.write(LARGE_OFFSET, payload, 0, payload.size)
        }

        ops.file(file, readWrite = false).use { handle ->
            handle.size() shouldBe LARGE_OFFSET + payload.size
            val readBack = ByteArray(payload.size)
            handle.read(LARGE_OFFSET, readBack, 0, readBack.size) shouldBe payload.size
            readBack.decodeToString() shouldBe payload.decodeToString()
        }

        ops.delete(file) shouldBe true
        rig.pool.close()
    }

    @Test
    fun `guest access works on a public share`() = runTest {
        assumeTrue(dockerAvailable)
        val rig = rig(sambaContainer, share = PUBLIC_SHARE, authType = SmbLocation.AuthType.GUEST)

        rig.ops.exists(path()) shouldBe true

        rig.pool.close()
    }

    /** The keep-alive resource is released whenever the last operation finishes, not just on exit. */
    @Test
    fun `browsing works again after the gateway resource was released`() = runTest {
        assumeTrue(dockerAvailable)
        val rig = rig(sambaContainer)
        val gatewayScope = CoroutineScope(Dispatchers.IO)
        val gateway = SmbGateway(gatewayScope, TestDispatcherProvider(), rig.ops, rig.pool)

        gateway.sharedResource.useRes { gateway.lookupFiles(path(), LookupOptions()) }

        gateway.sharedResource.close()
        withContext(Dispatchers.IO) {
            withTimeout(RESOURCE_TEARDOWN_TIMEOUT) {
                while (!gateway.sharedResource.isClosed) delay(100)
                // The source teardown (which closes the pool) runs off-lock after the detach
                delay(1000)
            }
        }

        gateway.sharedResource.useRes { gateway.lookupFiles(path(), LookupOptions()) }

        gatewayScope.cancel()
        rig.pool.close()
    }

    @Test
    fun `a wrong password is reported as an auth failure`() = runTest {
        assumeTrue(dockerAvailable)
        val rig = rig(sambaContainer)
        rig.credentialStore.store(locationId, 1, USERNAME, null, "wrong".toCharArray(), remember = false)

        shouldThrow<SmbAuthException> { rig.ops.lookupFiles(path(), LookupOptions()) }

        rig.pool.close()
    }

    @Test
    fun `an unknown share is reported as missing`() = runTest {
        assumeTrue(dockerAvailable)
        val rig = rig(sambaContainer, share = "nope")

        shouldThrow<SmbShareNotFoundException> { rig.ops.lookupFiles(path(), LookupOptions()) }

        rig.pool.close()
    }

    @Test
    fun `an SMB1-only server is rejected instead of downgraded`() = runTest {
        assumeTrue(dockerAvailable)
        val rig = rig(smb1Container)

        shouldThrow<SmbDialectNotSupportedException> { rig.ops.lookupFiles(path(), LookupOptions()) }

        rig.pool.close()
    }

    companion object {
        private const val SMB_PORT = 445
        private const val SHARE_NAME = "private"
        private const val PUBLIC_SHARE = "public"
        private const val USERNAME = "butler"
        private const val PASSWORD = "butlerpass"

        /** Well past Int.MAX_VALUE, the offset every 32-bit truncation bug shows up at. */
        private const val LARGE_OFFSET = 3L * 1024 * 1024 * 1024

        /** Generous room for the shared resource's own stop timeout. */
        private const val RESOURCE_TEARDOWN_TIMEOUT = 30_000L

        val dockerAvailable: Boolean by lazy {
            try {
                DockerClientFactory.instance().isDockerAvailable
            } catch (e: Throwable) {
                false
            }
        }

        /** dperson/samba builds its own smb.conf from these flags, a copied file would be ignored. */
        private fun sambaContainer(protocol: String): GenericContainer<*> =
            GenericContainer("dperson/samba:latest")
                .withExposedPorts(SMB_PORT)
                .withCommand(
                    "-u", "$USERNAME;$PASSWORD",
                    "-s", "$SHARE_NAME;/srv/private;no;no;no;$USERNAME",
                    "-s", "$PUBLIC_SHARE;/srv/public;yes;no;yes",
                    "-g", "server min protocol = $protocol",
                    "-g", "server max protocol = $protocol",
                    "-g", "map to guest = Bad User",
                    "-p",
                )
                .waitingFor(Wait.forListeningPort())

        val sambaContainer: GenericContainer<*> by lazy {
            sambaContainer("SMB3").also { it.start() }
        }

        val smb1Container: GenericContainer<*> by lazy {
            sambaContainer("NT1").also { it.start() }
        }

        @JvmStatic
        @AfterClass
        fun stopContainers() {
            if (!dockerAvailable) return
            runCatching { sambaContainer.stop() }
            runCatching { smb1Container.stop() }
        }
    }
}
