package eu.darken.butler.common.files.sftp

import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.SftpPath
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.operations.MockFileSystemOps
import eu.darken.butler.common.files.operations.TransferStrategy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.uuid.Uuid

class SftpPathCopyStrategyTest : BaseTest() {

    private val idA = Uuid.parse("aaaaaaaa-0000-0000-0000-000000000001")
    private val idB = Uuid.parse("bbbbbbbb-0000-0000-0000-000000000002")

    private lateinit var ops: MockFileSystemOps<SftpPath, SftpPathLookup>
    private val strategy = SftpPathCopyStrategy()

    private fun a(vararg segments: String) = SftpPath(idA, segments.toList())
    private fun b(vararg segments: String) = SftpPath(idB, segments.toList())

    @BeforeEach
    fun setup() {
        ops = MockFileSystemOps { path, type, size, modifiedAt, _, _, _ ->
            SftpPathLookup(lookedUp = path, fileType = type, size = size, modifiedAt = modifiedAt)
        }
        ops.defaultExistsStrict = Existence.ABSENT
        ops.addMockDir(a("src").path)
        ops.addMockDir(a("dest").path)
        ops.addMockDir(b("dest").path)
        ops.addMockFile(a("src", "file.txt").path, "file".toByteArray())
        ops.addMockSymlink(a("src", "link").path, "file.txt")
    }

    private fun linkLookup(target: SftpPath?) = SftpPathLookup(
        lookedUp = a("src", "link"),
        fileType = FileType.SYMBOLIC_LINK,
        size = 8L,
        modifiedAt = null,
        target = target,
        linkTarget = "file.txt",
    )

    private suspend fun copy(lookup: SftpPathLookup, destination: SftpPath, followSymlinks: Boolean = false) =
        strategy.transferFile(
            sourceLookup = lookup,
            destination = destination,
            sourceOps = ops,
            destOps = ops,
            options = TransferStrategy.Options(followSymlinks = followSymlinks),
            onProgress = {},
        )

    @Test
    fun `a link inside its location is recreated on the same location`() = runTest {
        copy(linkLookup(a("src", "file.txt")), a("dest", "link"))
            .shouldBeInstanceOf<TransferStrategy.TransferResult.Success<SftpPath, SftpPath>>()

        ops.getFileType(a("dest", "link").path) shouldBe FileType.SYMBOLIC_LINK
        ops.getFileContent(a("dest", "link").path)!!.decodeToString() shouldBe a("src", "file.txt").path
    }

    @Test
    fun `a link is streamed when following links, across locations or leaving its location`() = runTest {
        copy(linkLookup(a("src", "file.txt")), a("dest", "followed"), followSymlinks = true)
        copy(linkLookup(a("src", "file.txt")), b("dest", "other-location"))
        copy(linkLookup(target = null), a("dest", "outside"))

        ops.getFileType(a("dest", "followed").path) shouldBe FileType.FILE
        ops.getFileType(b("dest", "other-location").path) shouldBe FileType.FILE
        ops.getFileType(a("dest", "outside").path) shouldBe FileType.FILE
    }

    @Test
    fun `an existing destination is reported as a conflict`() = runTest {
        ops.existsStrictAnswers[a("dest", "link").path] = Existence.PRESENT

        shouldThrow<PathAlreadyExistsException> { copy(linkLookup(a("src", "file.txt")), a("dest", "link")) }
    }
}
