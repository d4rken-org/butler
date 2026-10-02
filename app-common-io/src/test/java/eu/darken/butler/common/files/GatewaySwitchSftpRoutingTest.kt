package eu.darken.butler.common.files

import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.archive.ArchiveGateway
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.io.ProxyPfdFactory
import eu.darken.butler.common.files.local.LocalGateway
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.operations.GenericCrossTypeCopyStrategy
import eu.darken.butler.common.files.operations.MockFileSystemOps
import eu.darken.butler.common.files.operations.TransferStrategy
import eu.darken.butler.common.files.saf.SAFGateway
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import eu.darken.butler.common.files.sftp.SftpGateway
import eu.darken.butler.common.files.sftp.SftpPathLookup
import eu.darken.butler.common.files.smb.SmbGateway
import eu.darken.butler.common.files.smb.SmbPathLookup
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import kotlin.time.Instant
import kotlin.uuid.Uuid

class GatewaySwitchSftpRoutingTest : BaseTest() {

    private val locationA = Uuid.parse("11111111-2222-3333-4444-555555555555")
    private val locationB = Uuid.parse("66666666-7777-8888-9999-000000000000")

    private val sftpGateway: SftpGateway = mockk(relaxed = true)
    private val smbGateway: SmbGateway = mockk(relaxed = true)
    private val archiveGateway: ArchiveGateway = mockk(relaxed = true)

    private val gatewaySwitch = GatewaySwitch(
        appScope = TestScope(),
        dispatcherProvider = TestDispatcherProvider(),
        safGateway = mockk<SAFGateway>(relaxed = true),
        localGateway = mockk<LocalGateway>(relaxed = true),
        archiveGateway = archiveGateway,
        smbGateway = smbGateway,
        sftpGateway = sftpGateway,
        safLocationManager = mockk<SAFLocationManager>(relaxed = true),
        proxyPfdFactory = mockk<ProxyPfdFactory>(relaxed = true),
    )

    private val sftpOps = MockFileSystemOps<SftpPath, SftpPathLookup> { path, type, size, modifiedAt, _, _, createdAt ->
        SftpPathLookup(
            lookedUp = path,
            fileType = type,
            size = size,
            modifiedAt = modifiedAt ?: Instant.fromEpochMilliseconds(0),
            createdAt = createdAt,
        )
    }

    private val smbOps = MockFileSystemOps<SmbPath, SmbPathLookup> { path, type, size, modifiedAt, _, _, createdAt ->
        SmbPathLookup(
            lookedUp = path,
            fileType = type,
            size = size,
            modifiedAt = modifiedAt ?: Instant.fromEpochMilliseconds(0),
            createdAt = createdAt,
        )
    }

    private val localOps = MockFileSystemOps<LocalPath, LocalPathLookup> { path, type, size, modifiedAt, _, _, createdAt ->
        LocalPathLookup(
            lookedUp = path,
            fileType = type,
            size = size,
            modifiedAt = modifiedAt ?: Instant.fromEpochMilliseconds(0),
            target = null,
            createdAt = createdAt,
        )
    }

    @Test
    fun `an sftp path routes to the sftp gateway`() = runTest {
        gatewaySwitch.getGateway(SftpPath.root(locationA)) shouldBe sftpGateway
    }

    @Test
    fun `primitives reach the sftp gateway`() = runTest {
        val path = SftpPath(locationA, listOf("a.txt"))

        gatewaySwitch.exists(path)
        gatewaySwitch.delete(path, recursive = true)

        coVerify { sftpGateway.exists(path) }
        coVerify { sftpGateway.delete(path, true) }
    }

    @Test
    fun `a network path has no local or SAF alternative`() = runTest {
        val path = SftpPath(locationA, listOf("a.txt"))
        val original = ReadException("offline", path)
        coEvery { sftpGateway.lookup(path, any()) } throws original

        shouldThrow<ReadException> {
            gatewaySwitch.lookup(path, LookupOptions(), GatewaySwitch.Type.AUTO)
        } shouldBe original
    }

    @Test
    fun `copies between two sftp locations stay with the sftp gateway`() = runTest {
        val source = SftpPath(locationA, listOf("a.txt"))
        val destination = SftpPath(locationB, listOf("in"))
        coEvery { sftpGateway.copy(any(), any(), any(), any()) } returns emptyFlow()

        gatewaySwitch.copy(setOf(source), destination, null, CopyAction.Options()).toList()

        coVerify { sftpGateway.copy(setOf(source), destination, any(), any()) }
    }

    @Test
    fun `moves between two sftp locations stay with the sftp gateway`() = runTest {
        val source = SftpPath(locationA, listOf("a.txt"))
        val destination = SftpPath(locationB, listOf("in"))
        coEvery { sftpGateway.move(any(), any(), any(), any()) } returns emptyFlow()

        gatewaySwitch.move(setOf(source), destination, null, MoveAction.Options()).toList()

        coVerify { sftpGateway.move(setOf(source), destination, any(), any()) }
    }

    @Test
    fun `archives refuse sftp content`() = runTest {
        val source = SftpPath(locationA, listOf("a.txt"))
        val archive = ArchivePath(LocalPath.build("/sdcard/a.zip"), emptyList())

        shouldThrow<WriteException> {
            gatewaySwitch.copy(setOf(source), archive, null, CopyAction.Options()).toList()
        }
        shouldThrow<WriteException> {
            gatewaySwitch.move(setOf(source), archive, null, MoveAction.Options()).toList()
        }
    }

    @Test
    fun `copying from sftp to local streams the content`() = runTest {
        val source = SftpPath(locationA, listOf("photos", "a.jpg"))
        sftpOps.addMockFile(source.path, "network bytes".toByteArray())
        localOps.addMockDir("/dest")

        val result = GenericCrossTypeCopyStrategy<SftpPath, SftpPathLookup, LocalPath, LocalPathLookup>()
            .transferFile(
                sourceLookup = sftpOps.lookup(source),
                destination = LocalPath.build("/dest/a.jpg"),
                sourceOps = sftpOps,
                destOps = localOps,
                options = TransferStrategy.Options(),
                onProgress = {},
            )

        result.shouldBeInstanceOf<TransferStrategy.TransferResult.Success<*, *>>()
        localOps.getFileContent("/dest/a.jpg") shouldBe "network bytes".toByteArray()
    }

    @Test
    fun `copying from smb to sftp streams the content`() = runTest {
        val source = SmbPath(locationB, listOf("a.mkv"))
        smbOps.addMockFile(source.path, "smb bytes".toByteArray())
        val destinationDir = SftpPath(locationA, listOf("movies"))
        sftpOps.addMockDir(destinationDir.path)

        val destination = destinationDir.child("a.mkv")
        val result = GenericCrossTypeCopyStrategy<SmbPath, SmbPathLookup, SftpPath, SftpPathLookup>()
            .transferFile(
                sourceLookup = smbOps.lookup(source),
                destination = destination,
                sourceOps = smbOps,
                destOps = sftpOps,
                options = TransferStrategy.Options(),
                onProgress = {},
            )

        result.shouldBeInstanceOf<TransferStrategy.TransferResult.Success<*, *>>()
        sftpOps.getFileContent(destination.path) shouldBe "smb bytes".toByteArray()
        sftpOps.lookup(destination).fileType shouldBe FileType.FILE
    }
}
