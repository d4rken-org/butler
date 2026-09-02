package eu.darken.butler.common.files.local.operations.strategies

import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.operations.MockFileSystemOps
import eu.darken.butler.common.files.operations.TransferStrategy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * What LocalPathCopyStrategy does with an occupied destination while overwrite is off.
 *
 * The copy opens the destination with TRUNCATE_EXISTING, so a destination that reads as free but
 * is not destroys whatever is there - with root, a device node under /dev/block included.
 */
class LocalPathCopyStrategyTest : BaseTest() {

    private lateinit var mockOps: MockFileSystemOps<LocalPath, LocalPathLookup>
    private lateinit var strategy: LocalPathCopyStrategy

    @BeforeEach
    fun setup() {
        mockOps = MockFileSystemOps { path, type, size, modifiedAt, permissions, ownership, createdAt ->
            LocalPathLookup(
                lookedUp = path,
                fileType = type,
                size = size,
                modifiedAt = modifiedAt ?: kotlin.time.Instant.fromEpochMilliseconds(0),
                target = null,
                ownership = ownership,
                permissions = permissions,
                createdAt = createdAt,
            )
        }
        // The baseline below is about a destination that is meant to be free; the cases that are
        // not say so per path.
        mockOps.defaultExistsStrict = Existence.ABSENT
        strategy = LocalPathCopyStrategy(LocalFileSystemOps(ownershipResolver = mockk(relaxed = true)))
        mockOps.addMockFile("/source/file.txt", "content".toByteArray())
        mockOps.addMockDir("/dest")
    }

    @AfterEach
    fun cleanup() {
        mockOps.clear()
    }

    private suspend fun copy(ops: MockFileSystemOps<LocalPath, LocalPathLookup>) =
        LocalPathCopyStrategy(LocalFileSystemOps(ownershipResolver = mockk(relaxed = true))).transferFile(
            sourceLookup = mockOps.lookup(LocalPath.build("/source/file.txt")),
            destination = LocalPath.build("/dest/file.txt"),
            sourceOps = ops,
            destOps = ops,
            options = TransferStrategy.Options(overwrite = false, preserveAttributes = false),
            onProgress = {},
        )

    @Test
    fun `a free destination is copied`() = runTest {
        val result = copy(mockOps)

        result.shouldBeInstanceOf<TransferStrategy.TransferResult.Success<*, *>>()
        mockOps.getFileContent("/dest/file.txt") shouldBe "content".toByteArray()
    }

    /**
     * A FIFO, socket or device node is FileType.UNKNOWN to the plain lookup, i.e. indistinguishable
     * from "nothing there".
     */
    @Test
    fun `a destination the plain lookup cannot classify is a conflict`() = runTest {
        mockOps.existsStrictAnswers["/dest/file.txt"] = Existence.PRESENT
        val spyOps = spyk(mockOps)

        shouldThrow<PathAlreadyExistsException> { copy(spyOps) }

        coVerify(exactly = 0) { spyOps.openOutputStream(any(), any()) }
        spyOps.hasFile("/dest/file.txt") shouldBe false
        spyOps.hasFile("/source/file.txt") shouldBe true
    }

    @Test
    fun `a destination that cannot be inspected stops the copy`() = runTest {
        mockOps.existsStrictAnswers["/dest/file.txt"] = Existence.UNKNOWN
        val spyOps = spyk(mockOps)

        shouldThrow<WriteException> { copy(spyOps) }

        coVerify(exactly = 0) { spyOps.openOutputStream(any(), any()) }
        spyOps.hasFile("/dest/file.txt") shouldBe false
        spyOps.hasFile("/source/file.txt") shouldBe true
    }

    @Test
    fun `a symlink is followed onto the same conflict check`() = runTest {
        mockOps.addMockSymlink("/source/link.txt", "/source/file.txt")
        mockOps.existsStrictAnswers["/dest/file.txt"] = Existence.PRESENT
        val spyOps = spyk(mockOps)

        shouldThrow<PathAlreadyExistsException> {
            strategy.transferFile(
                sourceLookup = mockOps.lookup(LocalPath.build("/source/link.txt")),
                destination = LocalPath.build("/dest/file.txt"),
                sourceOps = spyOps,
                destOps = spyOps,
                options = TransferStrategy.Options(
                    overwrite = false,
                    preserveAttributes = false,
                    followSymlinks = true,
                ),
                onProgress = {},
            )
        }

        coVerify(exactly = 0) { spyOps.openOutputStream(any(), any()) }
        spyOps.hasFile("/dest/file.txt") shouldBe false
    }
}
