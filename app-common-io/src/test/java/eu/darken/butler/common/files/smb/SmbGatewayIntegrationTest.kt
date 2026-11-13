package eu.darken.butler.common.files.smb

import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.CreateAction
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.extensions.Segments
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.local.LocalGateway
import eu.darken.butler.common.files.local.accessibility.LocalPathAccessChecker
import eu.darken.butler.common.files.local.routing.AccessMode
import eu.darken.butler.common.files.local.routing.LocalPathRoutingPolicy
import eu.darken.butler.common.files.local.routing.ModeSession
import eu.darken.butler.common.files.local.routing.ModeSessionFactory
import eu.darken.butler.common.files.local.routing.RouteDecision
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.smb.credentials.SmbCredentialCipher
import eu.darken.butler.common.files.smb.credentials.SmbCredentialStore
import eu.darken.butler.common.files.smb.credentials.db.SmbCredentialEntity
import eu.darken.butler.common.files.smb.credentials.db.SmbCredentialsDao
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.common.files.smb.location.SmbLocationManager
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.common.sharedresource.useRes
import eu.darken.butler.common.storage.StorageManager2
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
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
import java.io.File
import java.nio.file.Files
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Drives the real gateway against a real Samba server.
 *
 * Skipped locally when Docker is unavailable. The dedicated SMB CI job requires Docker and
 * rejects skipped integration results.
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
            share: String?,
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
            share: String?,
            basePath: Segments,
            domain: String?,
            username: String?,
            authType: SmbLocation.AuthType,
            rememberCredential: Boolean,
            password: CharArray?,
        ): SmbLocation = throw UnsupportedOperationException()

        override suspend fun delete(id: Uuid) = throw UnsupportedOperationException()
        override suspend fun recordSeen(id: Uuid, host: String, port: Int, at: Instant) =
            throw UnsupportedOperationException()
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

    /** @param share null for a server entry, whose root lists the container's shares. */
    private suspend fun rig(
        container: GenericContainer<*>,
        share: String? = SHARE_NAME,
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
        val locationManager = FakeLocationManager(location)
        val pool = SmbConnectionPool(
            appScope = TestScope(),
            locationManager = locationManager,
            credentialStore = credentialStore,
            clientFactory = SmbClientFactoryModule.clientFactory(),
            upgradeRepo = FakeUpgradeRepo(),
        )
        return Rig(SmbFileSystemOps(pool, locationManager, TestDispatcherProvider()), pool, credentialStore, location)
    }

    private fun Rig.gateway() = SmbGateway(TestScope(), TestDispatcherProvider(), ops, pool)

    /** SMB plus direct local storage, with no root, ADB or isolated service behind it. */
    private fun Rig.gatewaySwitch(): GatewaySwitch {
        val localOps = LocalFileSystemOps(mockk(relaxed = true))
        val localGateway = LocalGateway(
            appScope = TestScope(),
            dispatcherProvider = TestDispatcherProvider(),
            fileSystemOps = localOps,
            rootManager = mockk<RootManager>(relaxed = true),
            adbManager = mockk<AdbManager>(relaxed = true),
            accessChecker = mockk<LocalPathAccessChecker>(relaxed = true) {
                every { shouldTryNormalAccess(any(), any()) } returns true
            },
            isolatedServiceClient = mockk(relaxed = true),
            storageManager = mockk<StorageManager2>(relaxed = true),
            routingPolicy = mockk<LocalPathRoutingPolicy>(relaxed = true) {
                coEvery { classify(any(), any(), any()) } returns RouteDecision.Allowed(AccessMode.DIRECT)
                every { proactiveChildren(any()) } returns emptySet()
            },
            modeSessionFactory = mockk<ModeSessionFactory>(relaxed = true) {
                coEvery { open(AccessMode.DIRECT) } answers { ModeSession(AccessMode.DIRECT, localOps, null, null) }
            },
        )
        return GatewaySwitch(
            appScope = TestScope(),
            dispatcherProvider = TestDispatcherProvider(),
            safGateway = mockk(relaxed = true),
            localGateway = localGateway,
            archiveGateway = mockk(relaxed = true),
            smbGateway = gateway(),
            sftpGateway = mockk(relaxed = true),
            webdavGateway = mockk(relaxed = true),
            safLocationManager = mockk(relaxed = true),
            proxyPfdFactory = mockk(relaxed = true),
        )
    }

    private suspend fun SmbFileSystemOps.writeText(path: SmbPath, text: String) =
        openOutputStream(path).sink().buffer().use { it.writeUtf8(text) }

    private suspend fun SmbFileSystemOps.readText(path: SmbPath): String =
        openInputStream(path).source().buffer().use { it.readUtf8() }

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
    fun `strict existence tells a missing path from an unreachable host`() = runTest {
        assumeTrue(dockerAvailable)
        val rig = rig(sambaContainer)
        val ops = rig.ops

        val dir = path("strict")
        ops.createDir(dir)

        ops.existsStrict(dir) shouldBe Existence.PRESENT
        ops.existsStrict(dir.child("nothing-here")) shouldBe Existence.ABSENT
        // The share root has no path to query, the lease that reached it is the proof.
        ops.existsStrict(path()) shouldBe Existence.PRESENT

        ops.delete(dir, recursive = true) shouldBe true
        ops.existsStrict(dir) shouldBe Existence.ABSENT

        rig.pool.close()
    }

    @Test
    fun `a location root inside a deleted base path is an absence`() = runTest {
        assumeTrue(dockerAvailable)
        val rig = rig(sambaContainer)
        // Reaching the share says nothing about a directory inside it, which is what a base path is.
        rig.location.value = rig.location.value.copy(basePath = listOf("no-such-base"))

        rig.ops.existsStrict(path()) shouldBe Existence.ABSENT

        rig.pool.close()
    }

    @Test
    fun `an unreachable host is never reported as an absence`() = runTest {
        assumeTrue(dockerAvailable)
        val rig = rig(sambaContainer)
        // Nothing is listening there, so the probe cannot reach the share at all.
        rig.location.value = rig.location.value.copy(port = UNUSED_PORT)

        rig.ops.existsStrict(path("anything")) shouldBe Existence.UNKNOWN

        rig.pool.close()
    }

    @Test
    fun `an SMB1-only server is rejected instead of downgraded`() = runTest {
        assumeTrue(dockerAvailable)
        val rig = rig(smb1Container)

        shouldThrow<SmbDialectNotSupportedException> { rig.ops.lookupFiles(path(), LookupOptions()) }

        rig.pool.close()
    }

    // region server entries

    @Test
    fun `a server entry's root lists the browsable disk shares`() = runTest {
        assumeTrue(dockerAvailable)
        val rig = rig(sambaContainer, share = null)
        val ops = rig.ops
        val root = path()

        ops.lookup(root, LookupOptions()).fileType shouldBe FileType.DIRECTORY
        ops.exists(root) shouldBe true
        ops.existsStrict(root) shouldBe Existence.PRESENT
        ops.canWrite(root) shouldBe false

        val shares = ops.lookupFiles(root, LookupOptions())
        // `private` is browseable = no, IPC$ is no disk share
        shares.map { it.name } shouldBe listOf(PUBLIC_SHARE)
        shares.single().lookedUp shouldBe root.child(PUBLIC_SHARE)
        shares.single().fileType shouldBe FileType.DIRECTORY
        ops.listFiles(root) shouldBe listOf(root.child(PUBLIC_SHARE))

        rig.pool.close()
    }

    @Test
    fun `a share left out of the listing is still reachable by path`() = runTest {
        assumeTrue(dockerAvailable)
        val rig = rig(sambaContainer, share = null)
        val ops = rig.ops
        val share = path(SHARE_NAME)

        ops.existsStrict(share) shouldBe Existence.PRESENT
        ops.canWrite(share) shouldBe true

        val dir = share.child("server-entry-${Uuid.random()}")
        val nested = dir.child("a", "b")
        ops.createDir(nested, createParents = true)
        ops.lookup(nested, LookupOptions()).fileType shouldBe FileType.DIRECTORY

        val file = nested.child("hello.txt")
        ops.writeText(file, "hello share")
        ops.readText(file) shouldBe "hello share"
        ops.lookup(file, LookupOptions()).size shouldBe "hello share".length.toLong()
        ops.lookupFiles(nested, LookupOptions()).map { it.name } shouldBe listOf("hello.txt")
        ops.existsStrict(file) shouldBe Existence.PRESENT
        ops.existsStrict(nested.child("nothing-here")) shouldBe Existence.ABSENT

        // The same directory, seen through a location for the share itself
        val shareRig = rig(sambaContainer)
        val viaShare = SmbPath(locationId, listOf(dir.name, "a", "b", "hello.txt"))
        shareRig.ops.readText(viaShare) shouldBe "hello share"
        shareRig.pool.close()

        ops.delete(dir, recursive = true) shouldBe true
        ops.exists(dir) shouldBe false

        rig.pool.close()
    }

    @Test
    fun `nothing can be created next to a server's shares`() = runTest {
        assumeTrue(dockerAvailable)
        val rig = rig(sambaContainer, share = null)
        val ops = rig.ops
        val newShare = path("new-share-${Uuid.random()}")

        shouldThrow<WriteException> { ops.createDir(newShare) }
        shouldThrow<WriteException> { ops.createFile(newShare) }
        shouldThrow<WriteException> { ops.openOutputStream(newShare) }
        shouldThrow<WriteException> { ops.file(newShare, readWrite = true) }
        shouldThrow<WriteException> { ops.createDir(path(), createParents = true) }
        ops.setModifiedAt(path(PUBLIC_SHARE), Instant.fromEpochMilliseconds(0)) shouldBe false

        ops.lookupFiles(path(), LookupOptions()).map { it.name } shouldBe listOf(PUBLIC_SHARE)

        rig.pool.close()
    }

    @Test
    fun `a move between two shares of a server entry copies and removes the source`() = runTest {
        assumeTrue(dockerAvailable)
        val rig = rig(sambaContainer, share = null)
        val ops = rig.ops
        val gateway = rig.gateway()
        val run = "cross-share-${Uuid.random()}"

        val sourceDir = path(SHARE_NAME, run)
        ops.createDir(sourceDir.child("tree"), createParents = true)
        ops.writeText(sourceDir.child("file.txt"), "moved file")
        ops.writeText(sourceDir.child("tree", "inner.txt"), "moved tree")
        val targetDir = path(PUBLIC_SHARE, run)
        ops.createDir(targetDir)

        ops.move(sourceDir.child("file.txt"), targetDir.child("file.txt"))
            .shouldBeInstanceOf<MoveOutcome.NotSupported>()
        ops.exists(sourceDir.child("file.txt")) shouldBe true

        val result = gateway.move(
            setOf(sourceDir.child("file.txt"), sourceDir.child("tree")),
            targetDir,
            null,
            MoveAction.Options(),
        ).toList().last()

        result.shouldBeInstanceOf<MoveAction.State.Completed<*, *, *, *>>()
        ops.exists(sourceDir.child("file.txt")) shouldBe false
        ops.exists(sourceDir.child("tree")) shouldBe false
        ops.readText(targetDir.child("file.txt")) shouldBe "moved file"
        ops.readText(targetDir.child("tree", "inner.txt")) shouldBe "moved tree"

        ops.delete(sourceDir, recursive = true) shouldBe true
        ops.delete(targetDir, recursive = true) shouldBe true
        rig.pool.close()
    }

    @Test
    fun `the server root and its shares cannot be deleted or moved`() = runTest {
        assumeTrue(dockerAvailable)
        val rig = rig(sambaContainer, share = null)
        val ops = rig.ops
        val gateway = rig.gateway()
        val sentinel = path(SHARE_NAME, "sentinel-${Uuid.random()}", "keep.txt")
        ops.createDir(sentinel.parent!!)
        ops.writeText(sentinel, "keep me")
        val deleteAll = DeleteAction.Options<SmbPath>(recursive = true)

        shouldThrow<WriteException> { gateway.delete(setOf(path()), deleteAll).toList() }
        shouldThrow<WriteException> { gateway.delete(setOf(path(SHARE_NAME)), deleteAll).toList() }
        // A mixed batch is refused as a whole, its deletable item included
        shouldThrow<WriteException> { gateway.delete(setOf(sentinel, path(SHARE_NAME)), deleteAll).toList() }
        shouldThrow<WriteException> {
            gateway.move(setOf(path(SHARE_NAME)), path(PUBLIC_SHARE), null, MoveAction.Options()).toList()
        }
        shouldThrow<WriteException> {
            gateway.move(setOf(path()), path(PUBLIC_SHARE), null, MoveAction.Options()).toList()
        }
        shouldThrow<WriteException> {
            gateway.move(setOf(sentinel), path(), null, MoveAction.Options()).toList()
        }
        shouldThrow<WriteException> {
            gateway.copy(setOf(sentinel), path(), null, CopyAction.Options()).toList()
        }
        shouldThrow<WriteException> {
            gateway.create(path("new-share"), CreateAction.CreateType.DIRECTORY, CreateAction.Options()).toList()
        }
        shouldThrow<WriteException> { ops.delete(path(SHARE_NAME), recursive = true) }
        shouldThrow<WriteException> { ops.delete(path(), recursive = true) }
        shouldThrow<WriteException> { ops.move(path(SHARE_NAME), path(PUBLIC_SHARE, "private")) }

        ops.readText(sentinel) shouldBe "keep me"
        ops.existsStrict(path(SHARE_NAME)) shouldBe Existence.PRESENT

        ops.delete(sentinel.parent!!, recursive = true) shouldBe true
        rig.pool.close()
    }

    @Test
    fun `a share cannot be moved out of a server entry to local storage`() = runTest {
        assumeTrue(dockerAvailable)
        val rig = rig(sambaContainer, share = null)
        val ops = rig.ops
        val switch = rig.gatewaySwitch()
        val sentinel = path(SHARE_NAME, "sentinel-${Uuid.random()}", "keep.txt")
        ops.createDir(sentinel.parent!!)
        ops.writeText(sentinel, "keep me")
        val localDir = Files.createTempDirectory("smb-server-entry").toFile()
        try {
            val local = LocalPath.build(localDir)

            shouldThrow<WriteException> {
                switch.move(setOf<APath<*>>(path(SHARE_NAME)), local, null, MoveAction.Options()).toList()
            }
            shouldThrow<WriteException> {
                switch.move(setOf<APath<*>>(path()), local, null, MoveAction.Options()).toList()
            }

            val localFile = File(localDir, "upload.txt").apply { writeText("upload") }
            shouldThrow<WriteException> {
                switch.copy(setOf<APath<*>>(LocalPath.build(localFile)), path(), null, CopyAction.Options()).toList()
            }
            shouldThrow<WriteException> {
                switch.move(setOf<APath<*>>(LocalPath.build(localFile)), path(), null, MoveAction.Options()).toList()
            }

            ops.readText(sentinel) shouldBe "keep me"
            localFile.readText() shouldBe "upload"
            localDir.list()!!.toList() shouldBe listOf("upload.txt")
        } finally {
            localDir.deleteRecursively()
        }

        ops.delete(sentinel.parent!!, recursive = true) shouldBe true
        rig.pool.close()
    }

    // endregion

    companion object {
        private const val SMB_PORT = 445
        private const val SHARE_NAME = "private"
        private const val PUBLIC_SHARE = "public"
        private const val USERNAME = "butler"
        private const val PASSWORD = "butlerpass"

        /** Well past Int.MAX_VALUE, the offset every 32-bit truncation bug shows up at. */
        private const val LARGE_OFFSET = 3L * 1024 * 1024 * 1024

        /** Reserved by IANA as "do not use", so nothing is listening on it. */
        private const val UNUSED_PORT = 47

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
        internal fun sambaContainer(protocol: String): GenericContainer<*> =
            GenericContainer("dperson/samba@sha256:66088b78a19810dd1457a8f39340e95e663c728083efa5fe7dc0d40b2478e869")
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
