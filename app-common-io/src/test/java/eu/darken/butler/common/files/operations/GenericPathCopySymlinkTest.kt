package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.SftpPath
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.archive.ArchivePathLookup
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.files.sftp.SftpPathLookup
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A followed link leads to the target exactly as [FileSystemOps.readSymbolicLink] resolved it, however
 * the path type renders: `/src/tree/data`, `sftp://<id>/src/tree/data` or `smb://<id>/box.zip!/tree/data`.
 */
class GenericPathCopySymlinkTest : BaseTest() {

    private val locationId = Uuid.parse("aaaaaaaa-0000-0000-0000-000000000001")

    /** Resolves links like a server does: [readSymbolicLink] and every path below a link lead to its target. */
    private class LinkingMockOps<P : APath<P>, PL : APathLookup<P>>(
        lookupFactory: (P, FileType, Long?, Instant?, Permissions?, Ownership?, Instant?) -> PL,
    ) : MockFileSystemOps<P, PL>(lookupFactory) {

        private val links = mutableMapOf<P, P?>()

        /** A null [target] lies outside what [P] can address. */
        fun addLink(link: P, target: P?) {
            addMockSymlink(link.path, target?.path ?: "/outside")
            links[link] = target
        }

        private fun throughLinks(path: P): P {
            for ((link, target) in links) {
                if (target == null) continue
                if (path.segments.size > link.segments.size && path.path.startsWith("${link.path}/")) {
                    return throughLinks(target.child(*path.segments.drop(link.segments.size).toTypedArray()))
                }
            }
            return path
        }

        override suspend fun lookup(path: P, options: LookupOptions): PL = super.lookup(throughLinks(path), options)

        override suspend fun listFiles(path: P): List<P> = super.listFiles(throughLinks(path))

        override suspend fun readSymbolicLink(linkPath: P): P {
            if (!links.containsKey(linkPath)) throw ReadException("Not a link", linkPath)
            return links[linkPath] ?: throw ReadException("Link target is outside this location", linkPath)
        }
    }

    /** Hands every item to the cross-type copy and remembers what it was handed. */
    private class RecordingStrategy<SP : APath<SP>, SPL : APathLookup<SP>, DP : APath<DP>, DPL : APathLookup<DP>>(
        private val delegate: TransferStrategy<SP, SPL, DP, DPL> = GenericCrossTypeCopyStrategy(),
    ) : TransferStrategy<SP, SPL, DP, DPL> by delegate {

        val transferred = mutableListOf<SPL>()

        override suspend fun transferFile(
            sourceLookup: SPL,
            destination: DP,
            sourceOps: FileSystemOps<SP, SPL>,
            destOps: FileSystemOps<DP, DPL>,
            options: TransferStrategy.Options,
            onProgress: suspend (bytesTransferred: Long) -> Unit
        ): TransferStrategy.TransferResult<SP, DP> {
            transferred.add(sourceLookup)
            return delegate.transferFile(sourceLookup, destination, sourceOps, destOps, options, onProgress)
        }
    }

    private fun localOps() = LinkingMockOps<LocalPath, LocalPathLookup> { path, type, size, modifiedAt, _, _, _ ->
        LocalPathLookup(
            lookedUp = path,
            fileType = type,
            size = size,
            modifiedAt = modifiedAt ?: Instant.fromEpochMilliseconds(0),
            target = null,
        )
    }

    private fun sftpOps() = LinkingMockOps<SftpPath, SftpPathLookup> { path, type, size, modifiedAt, _, _, _ ->
        SftpPathLookup(lookedUp = path, fileType = type, size = size, modifiedAt = modifiedAt)
    }

    private fun archiveOps() = LinkingMockOps<ArchivePath, ArchivePathLookup> { path, type, size, modifiedAt, _, _, _ ->
        ArchivePathLookup(lookedUp = path, fileType = type, size = size, modifiedAt = modifiedAt)
    }

    private fun sftp(vararg segments: String) = SftpPath(locationId, segments.toList())

    /** `tree/{data/inner.txt, file.txt, dirLink -> data, fileLink -> file.txt}` below [tree]. */
    private fun <P : APath<P>, PL : APathLookup<P>> LinkingMockOps<P, PL>.addTree(tree: P) {
        addMockDir(tree.path)
        addMockDir(tree.child("data").path)
        addMockFile(tree.child("data", "inner.txt").path, "inner".toByteArray())
        addMockFile(tree.child("file.txt").path, "file".toByteArray())
        addLink(tree.child("dirLink"), tree.child("data"))
        addLink(tree.child("fileLink"), tree.child("file.txt"))
    }

    private fun MockFileSystemOps<*, *>.text(path: APath<*>): String? = getFileContent(path.path)?.decodeToString()

    @Test
    fun `local links are followed into their absolute targets`() = runTest {
        val ops = localOps()
        val tree = LocalPath.build("/src/tree")
        ops.addTree(tree)
        ops.addMockDir("/dest")

        setOf(tree).copyGeneric(
            destination = LocalPath.build("/dest"),
            sourceOps = ops,
            destOps = ops,
            strategy = GenericCrossTypeCopyStrategy(),
            options = TransferStrategy.Options(followSymlinks = true),
        ).last().shouldBeCompleted()

        ops.getFileType("/dest/tree/dirLink") shouldBe FileType.DIRECTORY
        ops.text(LocalPath.build("/dest/tree/dirLink/inner.txt")) shouldBe "inner"
        ops.getFileType("/dest/tree/fileLink") shouldBe FileType.FILE
        ops.text(LocalPath.build("/dest/tree/fileLink")) shouldBe "file"
    }

    @Test
    fun `SFTP links are followed into their resolved targets`() = runTest {
        val ops = sftpOps()
        val tree = sftp("src", "tree")
        ops.addTree(tree)
        ops.addMockDir(sftp("dest").path)

        setOf(tree).copyGeneric(
            destination = sftp("dest"),
            sourceOps = ops,
            destOps = ops,
            strategy = GenericCrossTypeCopyStrategy(),
            options = TransferStrategy.Options(followSymlinks = true),
        ).last().shouldBeCompleted()

        ops.getFileType(sftp("dest", "tree", "dirLink").path) shouldBe FileType.DIRECTORY
        ops.text(sftp("dest", "tree", "dirLink", "inner.txt")) shouldBe "inner"
        ops.getFileType(sftp("dest", "tree", "fileLink").path) shouldBe FileType.FILE
        ops.text(sftp("dest", "tree", "fileLink")) shouldBe "file"
    }

    @Test
    fun `an SFTP link leaving the location is handed over as the link itself`() = runTest {
        val ops = sftpOps()
        val tree = sftp("src", "tree")
        ops.addMockDir(tree.path)
        ops.addLink(tree.child("outside"), target = null)
        ops.addMockDir(sftp("dest").path)
        val strategy = RecordingStrategy<SftpPath, SftpPathLookup, SftpPath, SftpPathLookup>()

        setOf(tree).copyGeneric(
            destination = sftp("dest"),
            sourceOps = ops,
            destOps = ops,
            strategy = strategy,
            options = TransferStrategy.Options(followSymlinks = true),
        ).last().shouldBeCompleted()

        strategy.transferred.map { it.lookedUp to it.fileType } shouldBe listOf(
            tree.child("outside") to FileType.SYMBOLIC_LINK,
        )
    }

    @Test
    fun `an SFTP link to its own directory is not followed into itself`() = runTest {
        val ops = sftpOps()
        val tree = sftp("src", "tree")
        ops.addMockDir(tree.path)
        ops.addMockFile(tree.child("file.txt").path, "file".toByteArray())
        ops.addLink(tree.child("loop"), tree)
        ops.addMockDir(sftp("dest").path)
        val strategy = RecordingStrategy<SftpPath, SftpPathLookup, SftpPath, SftpPathLookup>()

        setOf(tree).copyGeneric(
            destination = sftp("dest"),
            sourceOps = ops,
            destOps = ops,
            strategy = strategy,
            options = TransferStrategy.Options(followSymlinks = true),
        ).last().shouldBeCompleted()

        strategy.transferred.map { it.lookedUp to it.fileType }.toSet() shouldBe setOf(
            tree.child("file.txt") to FileType.FILE,
            tree.child("loop") to FileType.SYMBOLIC_LINK,
        )
    }

    @Test
    fun `links inside an archive on a network share are followed`() = runTest {
        val archiveOps = archiveOps()
        val tree = ArchivePath(SmbPath(locationId, listOf("box.zip")), listOf("tree"))
        archiveOps.addMockDir(ArchivePath.root(tree.container).path)
        archiveOps.addTree(tree)
        val localOps = localOps()
        localOps.addMockDir("/dest")

        setOf(tree).copyGeneric(
            destination = LocalPath.build("/dest"),
            sourceOps = archiveOps,
            destOps = localOps,
            strategy = GenericCrossTypeCopyStrategy(),
            options = TransferStrategy.Options(followSymlinks = true),
        ).last().shouldBeCompleted()

        localOps.getFileType("/dest/tree/dirLink") shouldBe FileType.DIRECTORY
        localOps.text(LocalPath.build("/dest/tree/dirLink/inner.txt")) shouldBe "inner"
        localOps.text(LocalPath.build("/dest/tree/fileLink")) shouldBe "file"
    }

    private fun CopyAction.State<*, *, *, *>.shouldBeCompleted() {
        shouldBeInstanceOf<CopyAction.State.Completed<*, *, *, *>>()
    }
}
