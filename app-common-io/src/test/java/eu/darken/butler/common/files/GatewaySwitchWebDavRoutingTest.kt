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
import eu.darken.butler.common.files.operations.GenericCrossTypeMoveStrategy
import eu.darken.butler.common.files.operations.MockFileSystemOps
import eu.darken.butler.common.files.operations.TransferStrategy
import eu.darken.butler.common.files.saf.SAFGateway
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import eu.darken.butler.common.files.sftp.SftpGateway
import eu.darken.butler.common.files.smb.SmbGateway
import eu.darken.butler.common.files.webdav.WebDavGateway
import eu.darken.butler.common.files.webdav.WebDavPathLookup
import eu.darken.butler.common.files.webdav.WebDavUploadCopyStrategy
import eu.darken.butler.common.files.webdav.WebDavUploader
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
import java.io.IOException
import java.io.InputStream
import kotlin.time.Instant
import kotlin.uuid.Uuid

class GatewaySwitchWebDavRoutingTest : BaseTest() {

    private val locationA = Uuid.parse("11111111-2222-3333-4444-555555555555")
    private val locationB = Uuid.parse("66666666-7777-8888-9999-000000000000")

    private val webdavGateway: WebDavGateway = mockk(relaxed = true)
    private val archiveGateway: ArchiveGateway = mockk(relaxed = true)

    private val gatewaySwitch = GatewaySwitch(
        appScope = TestScope(),
        dispatcherProvider = TestDispatcherProvider(),
        safGateway = mockk<SAFGateway>(relaxed = true),
        localGateway = mockk<LocalGateway>(relaxed = true),
        archiveGateway = archiveGateway,
        smbGateway = mockk<SmbGateway>(relaxed = true),
        sftpGateway = mockk<SftpGateway>(relaxed = true),
        webdavGateway = webdavGateway,
        safLocationManager = mockk<SAFLocationManager>(relaxed = true),
        proxyPfdFactory = mockk<ProxyPfdFactory>(relaxed = true),
    )

    private val webdavOps = MockFileSystemOps<WebDavPath, WebDavPathLookup> { path, type, size, modifiedAt, _, _, _ ->
        WebDavPathLookup(lookedUp = path, fileType = type, size = size, modifiedAt = modifiedAt)
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

    /** Writes into [webdavOps] like the real upload, recording each declared length. */
    private inner class RecordingUploader(private val failAfter: Long? = null) : WebDavUploader {
        val lengths = mutableListOf<Long>()

        override suspend fun upload(
            path: WebDavPath,
            length: Long,
            input: InputStream,
            onProgress: suspend (Long) -> Unit,
        ) {
            lengths.add(length)
            val bytes = input.readBytes()
            if (failAfter != null) throw IOException("connection lost after $failAfter bytes")
            check(bytes.size.toLong() == length) { "Declared $length, got ${bytes.size}" }
            webdavOps.openOutputStream(path).use { it.write(bytes) }
            onProgress(length)
        }
    }

    @Test
    fun `a webdav path routes to the webdav gateway`() = runTest {
        gatewaySwitch.getGateway(WebDavPath.root(locationA)) shouldBe webdavGateway
    }

    @Test
    fun `primitives reach the webdav gateway`() = runTest {
        val path = WebDavPath(locationA, listOf("a.txt"))

        gatewaySwitch.exists(path)
        gatewaySwitch.delete(path, recursive = true)
        gatewaySwitch.file(path, readWrite = false)

        coVerify { webdavGateway.exists(path) }
        coVerify { webdavGateway.delete(path, true) }
        coVerify { webdavGateway.file(path, false) }
    }

    @Test
    fun `a network path has no local or SAF alternative`() = runTest {
        val path = WebDavPath(locationA, listOf("a.txt"))
        val original = ReadException("offline", path)
        coEvery { webdavGateway.lookup(path, any()) } throws original

        shouldThrow<ReadException> {
            gatewaySwitch.lookup(path, LookupOptions(), GatewaySwitch.Type.AUTO)
        } shouldBe original
    }

    @Test
    fun `copies between two webdav locations stay with the webdav gateway`() = runTest {
        val source = WebDavPath(locationA, listOf("a.txt"))
        val destination = WebDavPath(locationB, listOf("in"))
        coEvery { webdavGateway.copy(any(), any(), any(), any()) } returns emptyFlow()

        gatewaySwitch.copy(setOf(source), destination, null, CopyAction.Options()).toList()

        coVerify { webdavGateway.copy(setOf(source), destination, any(), any()) }
    }

    @Test
    fun `moves between two webdav locations stay with the webdav gateway`() = runTest {
        val source = WebDavPath(locationA, listOf("a.txt"))
        val destination = WebDavPath(locationB, listOf("in"))
        coEvery { webdavGateway.move(any(), any(), any(), any()) } returns emptyFlow()

        gatewaySwitch.move(setOf(source), destination, null, MoveAction.Options()).toList()

        coVerify { webdavGateway.move(setOf(source), destination, any(), any()) }
    }

    @Test
    fun `archives refuse webdav content`() = runTest {
        val source = WebDavPath(locationA, listOf("a.txt"))
        val archive = ArchivePath(LocalPath.build("/sdcard/a.zip"), emptyList())

        shouldThrow<WriteException> {
            gatewaySwitch.copy(setOf(source), archive, null, CopyAction.Options()).toList()
        }
        shouldThrow<WriteException> {
            gatewaySwitch.move(setOf(source), archive, null, MoveAction.Options()).toList()
        }
    }

    @Test
    fun `copying from webdav to local streams the content`() = runTest {
        val source = WebDavPath(locationA, listOf("photos", "a.jpg"))
        webdavOps.addMockFile(source.path, "network bytes".toByteArray())
        localOps.addMockDir("/dest")

        val result = GenericCrossTypeCopyStrategy<WebDavPath, WebDavPathLookup, LocalPath, LocalPathLookup>()
            .transferFile(
                sourceLookup = webdavOps.lookup(source),
                destination = LocalPath.build("/dest/a.jpg"),
                sourceOps = webdavOps,
                destOps = localOps,
                options = TransferStrategy.Options(),
                onProgress = {},
            )

        result.shouldBeInstanceOf<TransferStrategy.TransferResult.Success<*, *>>()
        localOps.getFileContent("/dest/a.jpg") shouldBe "network bytes".toByteArray()
    }

    @Test
    fun `uploads into webdav declare the source length after claiming the destination`() = runTest {
        localOps.addMockFile("/src/a.mkv", "local bytes".toByteArray())
        val destinationDir = WebDavPath(locationA, listOf("movies"))
        webdavOps.addMockDir(destinationDir.path)
        val uploader = RecordingUploader()
        var progress = 0L

        val destination = destinationDir.child("a.mkv")
        val result = WebDavUploadCopyStrategy<LocalPath, LocalPathLookup>(uploader).transferFile(
            sourceLookup = localOps.lookup(LocalPath.build("/src/a.mkv")),
            destination = destination,
            sourceOps = localOps,
            destOps = webdavOps,
            options = TransferStrategy.Options(),
            onProgress = { progress += it },
        )

        result.shouldBeInstanceOf<TransferStrategy.TransferResult.Success<*, *>>().bytesTransferred shouldBe 11L
        uploader.lengths shouldBe listOf(11L)
        progress shouldBe 11L
        webdavOps.getFileContent(destination.path) shouldBe "local bytes".toByteArray()
    }

    @Test
    fun `a failed upload removes the claimed destination`() = runTest {
        localOps.addMockFile("/src/a.mkv", "local bytes".toByteArray())
        webdavOps.addMockDir(WebDavPath(locationA, listOf("movies")).path)
        val destination = WebDavPath(locationA, listOf("movies", "a.mkv"))

        shouldThrow<IOException> {
            WebDavUploadCopyStrategy<LocalPath, LocalPathLookup>(RecordingUploader(failAfter = 4)).transferFile(
                sourceLookup = localOps.lookup(LocalPath.build("/src/a.mkv")),
                destination = destination,
                sourceOps = localOps,
                destOps = webdavOps,
                options = TransferStrategy.Options(),
                onProgress = {},
            )
        }

        webdavOps.hasFile(destination.path) shouldBe false
        localOps.hasFile("/src/a.mkv") shouldBe true
    }

    @Test
    fun `a source of unknown length goes through the spooled stream`() = runTest {
        localOps.addMockFile("/src/a.txt", "unsized".toByteArray())
        webdavOps.addMockDir(WebDavPath(locationA, listOf("in")).path)
        val uploader = RecordingUploader()
        val destination = WebDavPath(locationA, listOf("in", "a.txt"))
        val unsized = localOps.lookup(LocalPath.build("/src/a.txt"), LookupOptions(fetchSize = false))

        WebDavUploadCopyStrategy<LocalPath, LocalPathLookup>(uploader).transferFile(
            sourceLookup = unsized,
            destination = destination,
            sourceOps = localOps,
            destOps = webdavOps,
            options = TransferStrategy.Options(),
            onProgress = {},
        )

        uploader.lengths shouldBe emptyList()
        webdavOps.getFileContent(destination.path) shouldBe "unsized".toByteArray()
    }

    @Test
    fun `a move into webdav uploads and then deletes the source`() = runTest {
        localOps.addMockFile("/src/a.txt", "moving".toByteArray())
        webdavOps.addMockDir(WebDavPath(locationA, listOf("in")).path)
        val uploader = RecordingUploader()
        val destination = WebDavPath(locationA, listOf("in", "a.txt"))

        GenericCrossTypeMoveStrategy(WebDavUploadCopyStrategy<LocalPath, LocalPathLookup>(uploader)).transferFile(
            sourceLookup = localOps.lookup(LocalPath.build("/src/a.txt")),
            destination = destination,
            sourceOps = localOps,
            destOps = webdavOps,
            options = TransferStrategy.Options(),
            onProgress = {},
        )

        uploader.lengths shouldBe listOf(6L)
        webdavOps.getFileContent(destination.path) shouldBe "moving".toByteArray()
        localOps.hasFile("/src/a.txt") shouldBe false
        webdavOps.getFileType(destination.path) shouldBe FileType.FILE
    }
}
