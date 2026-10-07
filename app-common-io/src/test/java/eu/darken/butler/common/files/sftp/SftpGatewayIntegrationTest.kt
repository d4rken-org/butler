package eu.darken.butler.common.files.sftp

import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.error.causeChain
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.SftpPath
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.local.LocalGateway
import eu.darken.butler.common.files.local.routing.AccessMode
import eu.darken.butler.common.files.local.routing.LocalPathRoutingPolicy
import eu.darken.butler.common.files.local.routing.ModeSession
import eu.darken.butler.common.files.local.routing.ModeSessionFactory
import eu.darken.butler.common.files.local.routing.RouteDecision
import eu.darken.butler.common.files.local.accessibility.LocalPathAccessChecker
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.sftp.credentials.SftpCredential
import eu.darken.butler.common.files.sftp.credentials.SftpCredentialStore
import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.files.sftp.location.TrustedHostKey
import eu.darken.butler.common.files.smb.FakeUpgradeRepo
import eu.darken.butler.common.files.smb.SmbClientFactory
import eu.darken.butler.common.files.smb.SmbConnectionPool
import eu.darken.butler.common.files.smb.SmbFileSystemOps
import eu.darken.butler.common.files.smb.SmbGateway
import eu.darken.butler.common.files.smb.SmbGatewayIntegrationTest
import eu.darken.butler.common.files.smb.credentials.SmbCredential
import eu.darken.butler.common.files.smb.credentials.SmbCredentialStore
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.common.files.smb.location.SmbLocationManager
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.common.storage.StorageManager2
import eu.darken.ssh.HostKey
import eu.darken.ssh.HostKeyPolicy
import eu.darken.ssh.MinaSftpConnector
import eu.darken.ssh.SftpBlockingFile
import eu.darken.ssh.SftpEndpoint
import eu.darken.ssh.SftpFile
import eu.darken.ssh.SftpOpenMode
import eu.darken.ssh.SftpSession
import eu.darken.ssh.SshCredentials
import eu.darken.ssh.use
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okio.buffer
import okio.sink
import okio.source
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Instant
import kotlin.uuid.Uuid
import eu.darken.ssh.SftpPath as ServerPath

/**
 * Drives [GatewaySwitch] against a real OpenSSH server (atmoz/sftp, the image lib-ssh's contract tests
 * pin) and, for base path resolution, against an in-process server whose user starts in a home
 * directory.
 *
 * The Docker tests are skipped locally when Docker is unavailable, like [SmbGatewayIntegrationTest].
 */
class SftpGatewayIntegrationTest : BaseTest() {

    private val idA = Uuid.parse("aaaaaaaa-0000-0000-0000-000000000001")
    private val idB = Uuid.parse("bbbbbbbb-0000-0000-0000-000000000002")
    private val smbId = Uuid.parse("cccccccc-0000-0000-0000-000000000003")

    private val dispatchers = TestDispatcherProvider(Dispatchers.IO)

    private class Rig(
        val switch: GatewaySwitch,
        val pool: SftpConnectionPool,
        val locations: FakeSftpLocationManager,
        val passwords: MutableMap<Uuid, String>,
        val smbPool: SmbConnectionPool?,
    ) {
        suspend fun close() {
            pool.close()
            smbPool?.close()
        }
    }

    private fun rig(
        locations: List<SftpLocation>,
        smb: GenericContainer<*>? = null,
        clientFactory: SftpClientFactory = SftpClientFactoryModule.clientFactory(),
    ): Rig {
        val locationManager = FakeSftpLocationManager(locations)
        val passwords = mutableMapOf<Uuid, String>()
        val credentialStore = mockk<SftpCredentialStore>(relaxed = true) {
            every { evictions } returns MutableSharedFlow()
            coEvery { resolve(any()) } answers {
                val location = firstArg<SftpLocation>()
                SftpCredential.Password(location.username, (passwords[location.id] ?: PASSWORD).toCharArray())
            }
        }
        val pool = SftpConnectionPool(
            appScope = TestScope(),
            locationManager = locationManager,
            credentialStore = credentialStore,
            clientFactory = clientFactory,
            upgradeRepo = FakeUpgradeRepo(),
        )
        val sftpGateway = SftpGateway(TestScope(), dispatchers, SftpFileSystemOps(pool, dispatchers), pool)

        val smbPool = smb?.let { smbPool(it) }
        val smbGateway = smbPool
            ?.let { SmbGateway(TestScope(), dispatchers, SmbFileSystemOps(it, dispatchers), it) }
            ?: mockk(relaxed = true)

        val switch = GatewaySwitch(
            appScope = TestScope(),
            dispatcherProvider = dispatchers,
            safGateway = mockk(relaxed = true),
            localGateway = localGateway(),
            archiveGateway = mockk(relaxed = true),
            smbGateway = smbGateway,
            sftpGateway = sftpGateway,
            safLocationManager = mockk(relaxed = true),
            proxyPfdFactory = mockk(relaxed = true),
        )
        return Rig(switch, pool, locationManager, passwords, smbPool)
    }

    /** Direct access only, with no root, ADB or isolated service behind it. */
    private fun localGateway(): LocalGateway {
        val ops = LocalFileSystemOps(mockk(relaxed = true))
        return LocalGateway(
            appScope = TestScope(),
            dispatcherProvider = dispatchers,
            fileSystemOps = ops,
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
                coEvery { open(AccessMode.DIRECT) } answers { ModeSession(AccessMode.DIRECT, ops, null, null) }
            },
        )
    }

    private fun smbPool(container: GenericContainer<*>): SmbConnectionPool {
        val location = SmbLocation(
            id = smbId,
            label = "samba",
            host = container.host,
            port = container.getMappedPort(445),
            share = "private",
            authType = SmbLocation.AuthType.PASSWORD,
            rememberCredential = false,
            credentialVersion = 1,
            username = "butler",
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
        )
        return SmbConnectionPool(
            appScope = TestScope(),
            locationManager = mockk<SmbLocationManager> { coEvery { get(smbId) } returns location },
            credentialStore = mockk<SmbCredentialStore>(relaxed = true) {
                every { evictions } returns MutableSharedFlow()
                coEvery { resolve(any()) } answers { SmbCredential("butler", null, "butlerpass".toCharArray()) }
            },
            clientFactory = SmbClientFactory { eu.darken.smb.KotlinSmbClient() },
            upgradeRepo = FakeUpgradeRepo(),
        )
    }

    // region atmoz/sftp helpers

    private fun containerLocation(id: Uuid, basePath: String) = testSftpLocation(
        id = id,
        host = openSsh.host,
        port = openSsh.getMappedPort(22),
        username = USER,
        basePath = basePath,
        hostKey = TrustedHostKey.from(hostEd25519),
    )

    private suspend fun <R> rawSession(block: suspend (SftpSession) -> R): R = MinaSftpConnector().use { connector ->
        connector.connect(
            SftpEndpoint(openSsh.host, openSsh.getMappedPort(22)),
            SshCredentials.Password(USER, PASSWORD.toCharArray()),
            HostKeyPolicy.Pinned(hostEd25519),
        ).use { block(it) }
    }

    /**
     * Two fresh locations inside `/upload`, one addressed relative to the initial directory and one
     * absolute, so tests never see each other's files.
     */
    private suspend fun scratchLocations(): Pair<SftpLocation, SftpLocation> {
        val run = Uuid.random().toString()
        rawSession { session ->
            val base = ServerPath(listOf("upload", run))
            session.mkdir(base)
            session.mkdir(base.child("a"))
            session.mkdir(base.child("b"))
        }
        return containerLocation(idA, "upload/$run/a") to containerLocation(idB, "/upload/$run/b")
    }

    private fun a(vararg segments: String) = SftpPath(idA, segments.toList())
    private fun b(vararg segments: String) = SftpPath(idB, segments.toList())

    private suspend fun GatewaySwitch.write(path: APath<*>, text: String) =
        openOutputStream(path).sink().buffer().use { it.writeUtf8(text) }

    private suspend fun GatewaySwitch.read(path: APath<*>): String =
        openInputStream(path).source().buffer().use { it.readUtf8() }

    // endregion

    @Test
    fun `list, lookup, read, write, mkdir, rename and delete`(): Unit = runBlocking {
        assumeTrue(dockerAvailable)
        val (locationA, _) = scratchLocations()
        val rig = rig(listOf(locationA))
        val switch = rig.switch

        val dir = a("lifecycle")
        switch.createDir(dir)
        switch.lookup(dir, LookupOptions()).fileType shouldBe FileType.DIRECTORY

        val file = dir.child("hello.txt")
        switch.write(file, "hello sftp")
        switch.read(file) shouldBe "hello sftp"

        val lookup = switch.lookup(file, LookupOptions()).shouldBeInstanceOf<SftpPathLookup>()
        lookup.fileType shouldBe FileType.FILE
        lookup.size shouldBe "hello sftp".length.toLong()
        lookup.ownership?.userId shouldBe 1001L
        lookup.permissions shouldNotBe null

        switch.lookupFiles(dir, LookupOptions()).map { it.name } shouldBe listOf("hello.txt")

        val renamed = dir.child("renamed.txt")
        switch.move(file, renamed) shouldBe MoveOutcome.Moved
        switch.exists(file) shouldBe false
        switch.read(renamed) shouldBe "hello sftp"

        switch.getFileSystem(dir).totalSpace!! shouldBeGreaterThan 0L

        switch.delete(dir, recursive = true) shouldBe true
        switch.exists(dir) shouldBe false
        switch.existsStrict(dir) shouldBe Existence.ABSENT

        rig.close()
    }

    @Test
    fun `names other servers would refuse round trip`(): Unit = runBlocking {
        assumeTrue(dockerAvailable)
        val (locationA, _) = scratchLocations()
        val rig = rig(listOf(locationA))
        val names = listOf("back\\slash", "with space", "trailing.", "Überweisung ✓ 2024.txt", "a:b*c?")

        names.forEach { rig.switch.write(a(it), it) }

        rig.switch.lookupFiles(a(), LookupOptions()).map { it.name } shouldContainExactlyInAnyOrder names
        names.forEach { rig.switch.read(a(it)) shouldBe it }

        rig.close()
    }

    @Test
    fun `a link is listed as a link and walk and du do not follow a cycle`(): Unit = runBlocking {
        assumeTrue(dockerAvailable)
        val (locationA, _) = scratchLocations()
        val rig = rig(listOf(locationA))
        val switch = rig.switch

        val dir = a("cycle")
        switch.createDir(dir)
        switch.write(dir.child("file.txt"), "content")
        rig.pool.use(dir, retryOnTransportLoss = false) { lease ->
            lease.session.symlink(lease.serverPath(dir.child("loop")), ".")
        }

        val loop = switch.lookupFiles(dir, LookupOptions())
            .single { it.name == "loop" }
            .shouldBeInstanceOf<SftpPathLookup>()
        loop.fileType shouldBe FileType.SYMBOLIC_LINK
        loop.linkTarget shouldBe "."
        loop.target shouldBe dir
        switch.readSymbolicLink(dir.child("loop")) shouldBe dir

        val walked = withTimeout(WALK_TIMEOUT) {
            switch.walk(dir, LookupOptions(), APathGateway.WalkOptions()).toList()
        }
        walked.map { it.name } shouldContainExactlyInAnyOrder listOf("file.txt", "loop")

        val size = withTimeout(WALK_TIMEOUT) { switch.du(dir, APathGateway.DuOptions()) }
        val listing = switch.lookupFiles(dir, LookupOptions())
        size shouldBe switch.lookup(dir, LookupOptions()).size!! + listing.sumOf { it.size ?: 0L }

        // A link the gateway creates itself points at the target's server path
        val fileLink = dir.child("file-link")
        switch.createSymlink(fileLink, dir.child("file.txt")) shouldBe true
        switch.lookup(fileLink, LookupOptions()).fileType shouldBe FileType.SYMBOLIC_LINK
        switch.readSymbolicLink(fileLink) shouldBe dir.child("file.txt")
        switch.read(fileLink) shouldBe "content"

        rig.close()
    }

    @Test
    fun `deleting a directory that holds a link to another directory keeps the target`(): Unit = runBlocking {
        assumeTrue(dockerAvailable)
        val (locationA, _) = scratchLocations()
        val rig = rig(listOf(locationA))
        val switch = rig.switch

        val target = a("target")
        switch.createDir(target)
        switch.write(target.child("keep.txt"), "keep")

        val viaAction = a("via-action")
        switch.createDir(viaAction)
        switch.createSymlink(viaAction.child("link"), target)
        switch.delete(setOf(viaAction), DeleteAction.Options(recursive = true)).toList()

        val viaPrimitive = a("via-primitive")
        switch.createDir(viaPrimitive)
        switch.createSymlink(viaPrimitive.child("link"), target)
        switch.delete(viaPrimitive, recursive = true) shouldBe true

        switch.exists(viaAction) shouldBe false
        switch.exists(viaPrimitive) shouldBe false
        switch.read(target.child("keep.txt")) shouldBe "keep"

        rig.close()
    }

    @Test
    fun `copy and move between SFTP and local storage`(@TempDir tempDir: File): Unit = runBlocking {
        assumeTrue(dockerAvailable)
        val (locationA, _) = scratchLocations()
        val rig = rig(listOf(locationA))
        val switch = rig.switch
        val local = LocalPath.build(tempDir)

        File(tempDir, "up.txt").writeText("from the device")
        switch.copy(setOf(local.child("up.txt")), a(), null, CopyAction.Options()).toList()
        switch.read(a("up.txt")) shouldBe "from the device"

        switch.write(a("down.txt"), "from the server")
        switch.copy(setOf(a("down.txt")), local, null, CopyAction.Options()).toList()
        File(tempDir, "down.txt").readText() shouldBe "from the server"

        File(tempDir, "moved-up.txt").writeText("moved up")
        switch.move(setOf(local.child("moved-up.txt")), a(), null, MoveAction.Options()).toList()
        switch.read(a("moved-up.txt")) shouldBe "moved up"
        File(tempDir, "moved-up.txt").exists() shouldBe false

        switch.write(a("moved-down.txt"), "moved down")
        switch.move(setOf(a("moved-down.txt")), local, null, MoveAction.Options()).toList()
        File(tempDir, "moved-down.txt").readText() shouldBe "moved down"
        switch.exists(a("moved-down.txt")) shouldBe false

        rig.close()
    }

    // region links during copies

    private suspend fun Rig.link(link: SftpPath, rawTarget: String) = pool.use(link, retryOnTransportLoss = false) {
        it.session.symlink(it.serverPath(link), rawTarget)
    }

    /**
     * `tree/{data/inner.txt, file.txt, fileLink -> file.txt, dirLink -> data, dangling -> missing.txt,
     * loop -> ., outside -> ../../b/far.txt, outsideDir -> ../../b}` on location A, whose server
     * directory is a sibling of location B's.
     */
    private suspend fun Rig.linkTree(): SftpPath {
        val tree = a("tree")
        switch.createDir(tree)
        switch.createDir(tree.child("data"))
        switch.write(tree.child("data", "inner.txt"), "inner")
        switch.write(tree.child("file.txt"), "file")
        switch.write(b("far.txt"), "far")
        link(tree.child("fileLink"), "file.txt")
        link(tree.child("dirLink"), "data")
        link(tree.child("dangling"), "missing.txt")
        link(tree.child("loop"), ".")
        link(tree.child("outside"), "../../b/far.txt")
        link(tree.child("outsideDir"), "../../b")
        return tree
    }

    /** Skips every item that fails and returns the names of the skipped items. */
    private suspend fun GatewaySwitch.copySkippingFailures(
        source: APath<*>,
        destination: APath<*>,
        followSymlinks: Boolean,
    ): Set<String> {
        val onIssue: suspend (PathActionIssue) -> PathActionIssue.Resolution = { issue ->
            when (issue) {
                is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
                is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Skip()
                else -> throw IllegalStateException("Unexpected issue: $issue")
            }
        }
        val completed = withTimeout(WALK_TIMEOUT) {
            copy(setOf(source), destination, onIssue, CopyAction.Options(followSymlinks = followSymlinks)).toList()
        }.last().shouldBeInstanceOf<CopyAction.State.Completed<*, *, *, *>>()
        return completed.skipped.map { it.lookedUp.name }.toSet()
    }

    private fun File.listTypes(): Map<String, String> = listFiles()!!.associate { file ->
        file.name to when {
            Files.isSymbolicLink(file.toPath()) -> "link"
            file.isDirectory -> "dir"
            else -> "file"
        }
    }

    private suspend fun GatewaySwitch.listTypes(dir: APath<*>): Map<String, FileType> =
        lookupFiles(dir, LookupOptions()).associate { it.name to it.fileType }

    @Test
    fun `SFTP links copied to local storage without following them`(@TempDir tempDir: File): Unit = runBlocking {
        assumeTrue(dockerAvailable)
        val (locationA, locationB) = scratchLocations()
        val rig = rig(listOf(locationA, locationB))
        val tree = rig.linkTree()

        val skipped = rig.switch.copySkippingFailures(tree, LocalPath.build(tempDir), followSymlinks = false)

        skipped shouldBe setOf("dirLink", "dangling", "loop", "outsideDir")
        val copied = File(tempDir, "tree")
        copied.listTypes() shouldBe mapOf("data" to "dir", "file.txt" to "file", "fileLink" to "file", "outside" to "file")
        File(copied, "fileLink").readText() shouldBe "file"
        File(copied, "outside").readText() shouldBe "far"

        rig.close()
    }

    @Test
    fun `SFTP links copied to local storage following them`(@TempDir tempDir: File): Unit = runBlocking {
        assumeTrue(dockerAvailable)
        val (locationA, locationB) = scratchLocations()
        val rig = rig(listOf(locationA, locationB))
        val tree = rig.linkTree()

        val skipped = rig.switch.copySkippingFailures(tree, LocalPath.build(tempDir), followSymlinks = true)

        skipped shouldBe setOf("dangling", "loop", "outsideDir")
        val copied = File(tempDir, "tree")
        copied.listTypes() shouldBe mapOf(
            "data" to "dir",
            "file.txt" to "file",
            "fileLink" to "file",
            "dirLink" to "dir",
            "outside" to "file",
        )
        File(copied, "fileLink").readText() shouldBe "file"
        File(copied, "dirLink/inner.txt").readText() shouldBe "inner"
        File(copied, "outside").readText() shouldBe "far"

        rig.close()
    }

    @Test
    fun `SFTP links copied to another SFTP location without following them`(): Unit = runBlocking {
        assumeTrue(dockerAvailable)
        val (locationA, locationB) = scratchLocations()
        val rig = rig(listOf(locationA, locationB))
        val switch = rig.switch
        val tree = rig.linkTree()

        val skipped = switch.copySkippingFailures(tree, b(), followSymlinks = false)

        skipped shouldBe setOf("dirLink", "dangling", "loop", "outsideDir")
        switch.listTypes(b("tree")) shouldBe mapOf(
            "data" to FileType.DIRECTORY,
            "file.txt" to FileType.FILE,
            "fileLink" to FileType.FILE,
            "outside" to FileType.FILE,
        )
        switch.read(b("tree", "fileLink")) shouldBe "file"
        switch.read(b("tree", "outside")) shouldBe "far"

        rig.close()
    }

    @Test
    fun `SFTP links copied to another SFTP location following them`(): Unit = runBlocking {
        assumeTrue(dockerAvailable)
        val (locationA, locationB) = scratchLocations()
        val rig = rig(listOf(locationA, locationB))
        val switch = rig.switch
        val tree = rig.linkTree()

        val skipped = switch.copySkippingFailures(tree, b(), followSymlinks = true)

        skipped shouldBe setOf("dangling", "loop", "outsideDir")
        switch.listTypes(b("tree")) shouldBe mapOf(
            "data" to FileType.DIRECTORY,
            "file.txt" to FileType.FILE,
            "fileLink" to FileType.FILE,
            "dirLink" to FileType.DIRECTORY,
            "outside" to FileType.FILE,
        )
        switch.read(b("tree", "fileLink")) shouldBe "file"
        switch.read(b("tree", "dirLink", "inner.txt")) shouldBe "inner"
        switch.read(b("tree", "outside")) shouldBe "far"

        rig.close()
    }

    @Test
    fun `SFTP links copied within their location are recreated`(): Unit = runBlocking {
        assumeTrue(dockerAvailable)
        val (locationA, locationB) = scratchLocations()
        val rig = rig(listOf(locationA, locationB))
        val switch = rig.switch
        val tree = rig.linkTree()
        switch.createDir(a("copy"))

        val skipped = switch.copySkippingFailures(tree, a("copy"), followSymlinks = false)

        skipped shouldBe setOf("outsideDir")
        val copied = a("copy", "tree")
        switch.listTypes(copied) shouldBe mapOf(
            "data" to FileType.DIRECTORY,
            "file.txt" to FileType.FILE,
            "fileLink" to FileType.SYMBOLIC_LINK,
            "dirLink" to FileType.SYMBOLIC_LINK,
            "dangling" to FileType.SYMBOLIC_LINK,
            "loop" to FileType.SYMBOLIC_LINK,
            "outside" to FileType.FILE,
        )
        switch.readSymbolicLink(copied.child("fileLink")) shouldBe tree.child("file.txt")
        switch.readSymbolicLink(copied.child("dirLink")) shouldBe tree.child("data")
        switch.readSymbolicLink(copied.child("dangling")) shouldBe tree.child("missing.txt")
        switch.readSymbolicLink(copied.child("loop")) shouldBe tree
        switch.read(copied.child("outside")) shouldBe "far"

        rig.close()
    }

    // endregion

    @Test
    fun `copy between SFTP and SMB`(): Unit = runBlocking {
        assumeTrue(dockerAvailable)
        val (locationA, _) = scratchLocations()
        val rig = rig(listOf(locationA), smb = samba)
        val switch = rig.switch
        val smbDir = SmbPath(smbId, listOf("sftp-${Uuid.random()}"))
        switch.createDir(smbDir)

        switch.write(a("to-smb.txt"), "sftp to smb")
        switch.copy(setOf(a("to-smb.txt")), smbDir, null, CopyAction.Options()).toList()
        switch.read(smbDir.child("to-smb.txt")) shouldBe "sftp to smb"

        switch.write(smbDir.child("to-sftp.txt"), "smb to sftp")
        switch.copy(setOf(smbDir.child("to-sftp.txt")), a(), null, CopyAction.Options()).toList()
        switch.read(a("to-sftp.txt")) shouldBe "smb to sftp"

        switch.delete(smbDir, recursive = true)
        rig.close()
    }

    @Test
    fun `moving between two SFTP locations copies and then deletes`(): Unit = runBlocking {
        assumeTrue(dockerAvailable)
        val (locationA, locationB) = scratchLocations()
        val rig = rig(listOf(locationA, locationB))
        val switch = rig.switch

        switch.write(a("copied.txt"), "copied")
        switch.copy(setOf(a("copied.txt")), b(), null, CopyAction.Options()).toList()
        switch.read(b("copied.txt")) shouldBe "copied"
        switch.read(a("copied.txt")) shouldBe "copied"

        switch.move(a("moved.txt"), b("moved.txt")).shouldBeInstanceOf<MoveOutcome.NotSupported>()

        switch.createDir(a("tree"))
        switch.write(a("tree", "moved.txt"), "moved")
        switch.move(setOf(a("tree")), b(), null, MoveAction.Options()).toList()
        switch.read(b("tree", "moved.txt")) shouldBe "moved"
        switch.exists(a("tree")) shouldBe false

        rig.close()
    }

    @Test
    fun `a rename never replaces an existing destination`(): Unit = runBlocking {
        assumeTrue(dockerAvailable)
        val (locationA, _) = scratchLocations()
        val rig = rig(listOf(locationA))
        val switch = rig.switch
        switch.write(a("source.txt"), "source")
        switch.write(a("existing.txt"), "existing")

        shouldThrow<PathAlreadyExistsException> { switch.move(a("source.txt"), a("existing.txt")) }

        runCatching {
            switch.move(setOf(a("source.txt")), a("existing.txt"), null, MoveAction.Options(overwrite = false)).toList()
        }
        shouldThrow<PathAlreadyExistsException> { switch.createFile(a("existing.txt")) }

        switch.read(a("source.txt")) shouldBe "source"
        switch.read(a("existing.txt")) shouldBe "existing"

        rig.close()
    }

    /** `/` of a chrooted atmoz user is owned by root, so nothing can be written there. */
    @Test
    fun `a failed move never deletes the source`(): Unit = runBlocking {
        assumeTrue(dockerAvailable)
        val (locationA, _) = scratchLocations()
        val readOnly = containerLocation(idB, "")
        val rig = rig(listOf(locationA, readOnly))
        val switch = rig.switch
        switch.write(a("precious.txt"), "precious")

        val result = runCatching {
            switch.move(setOf(a("precious.txt")), b(), null, MoveAction.Options()).toList()
        }

        result.exceptionOrNull() shouldNotBe null
        switch.exists(b("precious.txt")) shouldBe false
        switch.read(a("precious.txt")) shouldBe "precious"

        rig.close()
    }

    /**
     * The copy is cancelled as soon as the first bytes are reported, which the generic copy does at
     * the latest when a file completes.
     */
    @Test
    fun `a copy cancelled midway leaves the source and the connection usable`(@TempDir tempDir: File): Unit =
        runBlocking {
            assumeTrue(dockerAvailable)
            val (locationA, _) = scratchLocations()
            val rig = rig(listOf(locationA))
            val switch = rig.switch

            val bulk = a("bulk")
            switch.createDir(bulk)
            val chunk = ByteArray(1024 * 1024) { it.toByte() }
            repeat(BULK_FILES) { index ->
                switch.openOutputStream(bulk.child("part-$index.bin")).use { stream ->
                    repeat(BULK_FILE_MIB) { stream.write(chunk) }
                }
            }
            val fileSize = BULK_FILE_MIB * chunk.size.toLong()

            val cancelled = File(tempDir, "cancelled").apply { mkdirs() }
            val copy = launch(Dispatchers.IO) {
                switch.copy(setOf(bulk), LocalPath.build(cancelled), null, CopyAction.Options()).collect { state ->
                    if (state is CopyAction.State.Active && state.copiedBytes > 0) {
                        this@launch.cancel()
                        yield()
                    }
                }
            }
            withTimeout(CANCEL_TIMEOUT) { copy.join() }

            copy.isCancelled shouldBe true
            (File(cancelled, "bulk").listFiles()?.size ?: 0) shouldBeLessThan BULK_FILES
            switch.lookupFiles(bulk, LookupOptions()).map { it.size } shouldBe List(BULK_FILES) { fileSize }

            val complete = File(tempDir, "complete").apply { mkdirs() }
            switch.copy(setOf(bulk), LocalPath.build(complete), null, CopyAction.Options()).toList()
            File(complete, "bulk").listFiles()!!.map { it.length() } shouldBe List(BULK_FILES) { fileSize }

            rig.close()
        }

    // region transport loss during a transfer

    /**
     * Real connections, except that a read handle of a file called [name] disconnects its session
     * after its first successful read, like a connection that drops mid-transfer. [sourceOpens]
     * counts how often that file was opened for reading.
     */
    private class DroppingClientFactory(private val name: String) : SftpClientFactory {
        private val real = SftpClientFactoryModule.clientFactory()
        val sourceOpens = AtomicInteger()

        override fun create(): SftpClient {
            val client = real.create()
            return object : SftpClient {
                override suspend fun connect(
                    endpoint: SftpEndpoint,
                    credentials: SshCredentials,
                    hostKeyPolicy: HostKeyPolicy,
                ): SftpSession = DroppingSession(client.connect(endpoint, credentials, hostKeyPolicy))

                override fun close() = client.close()
            }
        }

        private inner class DroppingSession(private val session: SftpSession) : SftpSession by session {
            override suspend fun openFile(path: ServerPath, mode: SftpOpenMode): SftpFile {
                val file = session.openFile(path, mode)
                if (mode != SftpOpenMode.READ || path.segments.lastOrNull() != name) return file
                sourceOpens.incrementAndGet()
                return DroppingFile(session, file)
            }
        }

        private class DroppingFile(session: SftpSession, file: SftpFile) : SftpFile by file {
            private val blocking = object : SftpBlockingFile by file.blocking() {
                private var readOnce = false

                override fun read(offset: Long, buffer: ByteArray, start: Int, length: Int): Int {
                    if (readOnce) session.disconnect()
                    return file.blocking().read(offset, buffer, start, length).also { if (it > 0) readOnce = true }
                }
            }

            override fun blocking(): SftpBlockingFile = blocking
        }
    }

    private suspend fun GatewaySwitch.writeBytes(path: APath<*>, bytes: ByteArray) =
        openOutputStream(path).sink().buffer().use { it.write(bytes) }

    private suspend fun GatewaySwitch.readBytes(path: APath<*>): ByteArray =
        openInputStream(path).source().buffer().use { it.readByteArray() }

    private fun Throwable.isTransportLoss() = causeChain.any { SftpStatusMapper.isTransportLost(it) }

    @Test
    fun `a connection lost while copying to local storage fails the copy`(@TempDir tempDir: File): Unit =
        runBlocking {
            assumeTrue(dockerAvailable)
            val (locationA, _) = scratchLocations()
            val payload = Random(16).nextBytes(DROPPED_FILE_BYTES)
            rig(listOf(locationA)).apply { switch.writeBytes(a("dropped.bin"), payload) }.close()
            val factory = DroppingClientFactory("dropped.bin")
            val rig = rig(listOf(locationA), clientFactory = factory)

            val states = mutableListOf<CopyAction.State<*, *, *, *>>()
            val failure = runCatching {
                withTimeout(WALK_TIMEOUT) {
                    rig.switch.copy(setOf(a("dropped.bin")), LocalPath.build(tempDir), null, CopyAction.Options())
                        .collect { states.add(it) }
                }
            }.exceptionOrNull()

            failure.shouldNotBeNull().isTransportLoss() shouldBe true
            states.none { it is CopyAction.State.Completed<*, *, *, *> } shouldBe true
            factory.sourceOpens.get() shouldBe 1
            rig.close()

            val fresh = rig(listOf(locationA))
            fresh.switch.readBytes(a("dropped.bin")).contentEquals(payload) shouldBe true
            fresh.close()
        }

    @Test
    fun `a connection lost while moving to another SFTP location keeps the source`(): Unit = runBlocking {
        assumeTrue(dockerAvailable)
        val (locationA, locationB) = scratchLocations()
        val payload = Random(16).nextBytes(DROPPED_FILE_BYTES)
        rig(listOf(locationA)).apply { switch.writeBytes(a("dropped.bin"), payload) }.close()
        val factory = DroppingClientFactory("dropped.bin")
        val rig = rig(listOf(locationA, locationB), clientFactory = factory)

        val states = mutableListOf<MoveAction.State<*, *, *, *>>()
        val failure = runCatching {
            withTimeout(WALK_TIMEOUT) {
                rig.switch.move(setOf(a("dropped.bin")), b(), null, MoveAction.Options()).collect { states.add(it) }
            }
        }.exceptionOrNull()

        failure.shouldNotBeNull().isTransportLoss() shouldBe true
        states.none { it is MoveAction.State.Completed<*, *, *, *> } shouldBe true
        factory.sourceOpens.get() shouldBe 1
        rig.close()

        val fresh = rig(listOf(locationA))
        fresh.switch.readBytes(a("dropped.bin")).contentEquals(payload) shouldBe true
        fresh.close()
    }

    // endregion

    @Test
    fun `an editor style overwrite replaces the whole file`(): Unit = runBlocking {
        assumeTrue(dockerAvailable)
        val (locationA, _) = scratchLocations()
        val rig = rig(listOf(locationA))
        val switch = rig.switch
        val note = a("note.txt")

        switch.write(note, "a long first version of the note")
        switch.write(note, "short")
        switch.read(note) shouldBe "short"

        switch.file(note, readWrite = true).use { handle ->
            handle.write(5, " and more".toByteArray(), 0, 9)
            handle.size() shouldBe 14L
        }
        switch.read(note) shouldBe "short and more"

        rig.close()
    }

    @Test
    fun `archives refuse SFTP content`(@TempDir tempDir: File): Unit = runBlocking {
        assumeTrue(dockerAvailable)
        val (locationA, _) = scratchLocations()
        val rig = rig(listOf(locationA))
        rig.switch.write(a("a.txt"), "a")
        val archive = ArchivePath(LocalPath.build(File(tempDir, "a.zip")), emptyList())

        shouldThrow<WriteException> {
            rig.switch.copy(setOf(a("a.txt")), archive, null, CopyAction.Options()).toList()
        }
        rig.switch.read(a("a.txt")) shouldBe "a"

        rig.close()
    }

    @Test
    fun `sign-in and host key failures are reported as such`(): Unit = runBlocking {
        assumeTrue(dockerAvailable)
        val (locationA, locationB) = scratchLocations()
        val rig = rig(listOf(locationA, locationB.copy(hostKey = testHostKey(7))))
        rig.passwords[idA] = "wrong"

        shouldThrow<SftpAuthException> { rig.switch.lookupFiles(a(), LookupOptions()) }
        shouldThrow<SftpHostKeyChangedException> { rig.switch.lookupFiles(b(), LookupOptions()) }.let {
            it.presentedKey shouldBe TrustedHostKey.from(hostEd25519)
            it.storedFingerprint shouldBe testHostKey(7).fingerprint
        }
        rig.switch.existsStrict(a("anything")) shouldBe Existence.UNKNOWN

        rig.close()
    }

    // region base path resolution

    private fun homeLocation(server: HomeDirectorySftpServer, basePath: String) = testSftpLocation(
        id = idA,
        host = "127.0.0.1",
        port = server.port,
        username = HomeDirectorySftpServer.USER,
        basePath = basePath,
        hostKey = TrustedHostKey.from(server.hostKey),
    )

    private suspend fun rootOf(rig: Rig): String {
        val lease = rig.pool.acquire(idA)
        return lease.root.toString().also { lease.close() }
    }

    private fun HomeDirectorySftpServer.populate() {
        File(home.toFile(), "docs").mkdirs()
        File(home.toFile(), "docs/in-docs.txt").writeText("docs")
        File(home.toFile(), "in-home.txt").writeText("home")
        File(files.toFile(), "srv/data").mkdirs()
        File(files.toFile(), "srv/data/in-srv.txt").writeText("srv")
    }

    @Test
    fun `the base path resolves against the initial directory`(): Unit = runBlocking {
        HomeDirectorySftpServer().use { server ->
            server.populate()

            suspend fun check(basePath: String, root: String, names: List<String>) {
                val rig = rig(listOf(homeLocation(server, basePath)))
                rootOf(rig) shouldBe root
                rig.switch.lookupFiles(a(), LookupOptions()).map { it.name } shouldContainExactlyInAnyOrder names
                rig.close()
            }

            check(basePath = "", root = "/home/butler", names = listOf("docs", "in-home.txt"))
            check(basePath = "docs", root = "/home/butler/docs", names = listOf("in-docs.txt"))
            check(basePath = "/srv/data", root = "/srv/data", names = listOf("in-srv.txt"))
        }
    }

    @Test
    fun `a missing base path is absent and a file as base path cannot be listed`(): Unit = runBlocking {
        HomeDirectorySftpServer().use { server ->
            server.populate()

            val missing = rig(listOf(homeLocation(server, "nope")))
            missing.switch.existsStrict(a()) shouldBe Existence.ABSENT
            shouldThrow<ReadException> { missing.switch.lookupFiles(a(), LookupOptions()) }
            missing.close()

            val file = rig(listOf(homeLocation(server, "in-home.txt")))
            shouldThrow<ReadException> { file.switch.lookupFiles(a(), LookupOptions()) }
            file.close()
        }
    }

    @Test
    fun `the connection test resolves and probes the base path`(): Unit = runBlocking {
        HomeDirectorySftpServer().use { server ->
            server.populate()
            val tester = SftpConnectionTester(SftpClientFactoryModule.clientFactory())

            suspend fun test(
                basePath: String,
                policy: HostKeyPolicy = HostKeyPolicy.Pinned(server.hostKey),
                password: String = HomeDirectorySftpServer.PASSWORD,
            ) = tester.test(
                host = "127.0.0.1",
                port = server.port,
                username = HomeDirectorySftpServer.USER,
                authType = SftpLocation.AuthType.PASSWORD,
                password = password.toCharArray(),
                privateKey = null,
                passphrase = null,
                basePath = basePath,
                hostKeyPolicy = policy,
            )

            test("docs") shouldBe SftpConnectionTester.Result.Success(ServerPath(listOf("home", "butler", "docs")))
            test("") shouldBe SftpConnectionTester.Result.Success(ServerPath(listOf("home", "butler")))
            test("nope") shouldBe SftpConnectionTester.Result.BasePathMissing
            test("in-home.txt") shouldBe SftpConnectionTester.Result.BasePathNotDirectory
            test("", policy = HostKeyPolicy.Unknown) shouldBe SftpConnectionTester.Result.HostKeyUnknown(server.hostKey)
            val wrongKey = testHostKey(9).toHostKey()
            test("", policy = HostKeyPolicy.Pinned(wrongKey)) shouldBe
                SftpConnectionTester.Result.HostKeyMismatch(wrongKey, server.hostKey)
            test("", password = "wrong") shouldBe SftpConnectionTester.Result.AuthenticationFailed
        }
    }

    // endregion

    companion object {
        private const val USER = "butler"
        private const val PASSWORD = "butlerpass"
        private const val BULK_FILES = 6
        private const val BULK_FILE_MIB = 8
        private const val WALK_TIMEOUT = 30_000L
        private const val CANCEL_TIMEOUT = 30_000L
        private const val DROPPED_FILE_BYTES = 4 * 1024 * 1024

        val dockerAvailable: Boolean by lazy {
            try {
                DockerClientFactory.instance().isDockerAvailable
            } catch (e: Throwable) {
                false
            }
        }

        private fun resource(name: String): ByteArray =
            requireNotNull(SftpGatewayIntegrationTest::class.java.getResourceAsStream("/keys/$name")) { name }
                .use { it.readBytes() }

        /** Parses an OpenSSH `.pub` line such as `ssh-ed25519 AAAA... comment`. */
        val hostEd25519: HostKey by lazy {
            val (type, blob) = resource("host_ed25519.pub").decodeToString().trim().split(' ')
            HostKey(type, Base64.getDecoder().decode(blob))
        }

        /** The image and user layout of lib-ssh's `OpenSshServer`: `/` read-only, `/upload` writable. */
        private val openSshContainer = lazy {
            GenericContainer("atmoz/sftp@sha256:75dcc29683ad479bdb99010666f2f55e99014350a1e862fe32b565845feb0fcd")
                .withExposedPorts(22)
                .withCommand("$USER:$PASSWORD:1001:100:upload")
                .apply {
                    for (name in listOf("host_ed25519", "host_rsa")) {
                        val target = "/etc/ssh/ssh_host_${name.removePrefix("host_")}_key"
                        withCopyToContainer(Transferable.of(resource(name), 0x180), target)
                        withCopyToContainer(Transferable.of(resource("$name.pub"), 0x1a4), "$target.pub")
                    }
                }
                .waitingFor(Wait.forLogMessage(".*Server listening on 0\\.0\\.0\\.0 port 22.*", 1))
                .also { it.start() }
        }
        val openSsh: GenericContainer<*> by openSshContainer

        private val sambaContainer = lazy { SmbGatewayIntegrationTest.sambaContainer("SMB3").also { it.start() } }
        val samba: GenericContainer<*> by sambaContainer

        @JvmStatic
        @AfterAll
        fun stopContainers() {
            if (openSshContainer.isInitialized()) runCatching { openSsh.stop() }
            if (sambaContainer.isInitialized()) runCatching { samba.stop() }
        }
    }
}
