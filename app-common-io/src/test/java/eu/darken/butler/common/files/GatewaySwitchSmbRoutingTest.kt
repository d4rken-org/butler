package eu.darken.butler.common.files

import eu.darken.butler.common.files.archive.ArchiveGateway
import eu.darken.butler.common.files.io.ProxyPfdFactory
import eu.darken.butler.common.files.local.LocalGateway
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.operations.GenericCrossTypeCopyStrategy
import eu.darken.butler.common.files.operations.MockFileSystemOps
import eu.darken.butler.common.files.operations.TransferStrategy
import eu.darken.butler.common.files.saf.SAFGateway
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import eu.darken.butler.common.files.smb.SmbGateway
import eu.darken.butler.common.files.smb.SmbPathLookup
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import kotlin.time.Instant
import kotlin.uuid.Uuid

class GatewaySwitchSmbRoutingTest : BaseTest() {

    private val locationId = Uuid.parse("11111111-2222-3333-4444-555555555555")

    private val smbGateway: SmbGateway = mockk(relaxed = true)
    private val proxyPfdFactory: ProxyPfdFactory = mockk(relaxed = true)

    private val gatewaySwitch = GatewaySwitch(
        appScope = TestScope(),
        dispatcherProvider = TestDispatcherProvider(),
        safGateway = mockk<SAFGateway>(relaxed = true),
        localGateway = mockk<LocalGateway>(relaxed = true),
        archiveGateway = mockk<ArchiveGateway>(relaxed = true),
        smbGateway = smbGateway,
        safLocationManager = mockk<SAFLocationManager>(relaxed = true),
        proxyPfdFactory = proxyPfdFactory,
    )

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
    fun `an smb path routes to the smb gateway`() = runTest {
        gatewaySwitch.getGateway(SmbPath.root(locationId)) shouldBe smbGateway
    }

    @Test
    fun `no preview descriptor is produced for network files`() = runTest {
        gatewaySwitch.openReadPFD(SmbPath(locationId, listOf("a.pdf"))) shouldBe null
    }

    @Test
    fun `copying from smb to local streams the content`() = runTest {
        val source = SmbPath(locationId, listOf("movies", "a.mkv"))
        smbOps.addMockFile(source.path, "network bytes".toByteArray())
        localOps.addMockDir("/dest")

        val destination = LocalPath.build("/dest/a.mkv")
        val result = GenericCrossTypeCopyStrategy<SmbPath, SmbPathLookup, LocalPath, LocalPathLookup>()
            .transferFile(
                sourceLookup = smbOps.lookup(source),
                destination = destination,
                sourceOps = smbOps,
                destOps = localOps,
                options = TransferStrategy.Options(),
                onProgress = {},
            )

        result.shouldBeInstanceOf<TransferStrategy.TransferResult.Success<*, *>>()
        localOps.getFileContent("/dest/a.mkv") shouldBe "network bytes".toByteArray()
    }

    @Test
    fun `copying from local to smb streams the content`() = runTest {
        localOps.addMockFile("/source/a.mkv", "local bytes".toByteArray())
        val destinationDir = SmbPath(locationId, listOf("movies"))
        smbOps.addMockDir(destinationDir.path)

        val destination = destinationDir.child("a.mkv")
        val result = GenericCrossTypeCopyStrategy<LocalPath, LocalPathLookup, SmbPath, SmbPathLookup>()
            .transferFile(
                sourceLookup = localOps.lookup(LocalPath.build("/source/a.mkv")),
                destination = destination,
                sourceOps = localOps,
                destOps = smbOps,
                options = TransferStrategy.Options(),
                onProgress = {},
            )

        result.shouldBeInstanceOf<TransferStrategy.TransferResult.Success<*, *>>()
        smbOps.getFileContent(destination.path) shouldBe "local bytes".toByteArray()
        smbOps.lookup(destination).fileType shouldBe FileType.FILE
    }
}
