package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.DataKeptException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import testhelpers.BaseTest
import kotlin.time.Instant

/**
 * Renames that only change the letter case, on a filesystem that matches names case-insensitively
 * like Android's shared storage. The device side of this is covered by LocalPathMoveDeviceTest.
 */
class GenericPathMoveCaseOnlyRenameTest : BaseTest() {

    private val lookupFactory: (LocalPath, FileType, Long?, Instant?, Permissions?, Ownership?, Instant?) -> LocalPathLookup =
        { path, type, size, modifiedAt, permissions, ownership, createdAt ->
            LocalPathLookup(
                lookedUp = path,
                fileType = type,
                size = size,
                modifiedAt = modifiedAt ?: Instant.fromEpochMilliseconds(0),
                target = null,
                ownership = ownership,
                permissions = permissions,
                createdAt = createdAt,
            )
        }

    /** Resolves a name in any letter case to the entry that exists, like a case-insensitive mount. */
    private inner class CaseInsensitiveOps : MockFileSystemOps<LocalPath, LocalPathLookup>(lookupFactory) {
        var existenceUnknown: (String) -> Boolean = { false }

        /** Names that still resolve although no entry is listed, like a stale entry in the FUSE cache. */
        var staleAlias: (String) -> Boolean = { false }

        private fun resolve(path: LocalPath): LocalPath =
            files.keys.firstOrNull { it.equals(path.path, ignoreCase = true) }?.let { LocalPath.build(it) } ?: path

        override suspend fun lookup(path: LocalPath, options: LookupOptions): LocalPathLookup =
            super.lookup(resolve(path), options)

        override suspend fun exists(path: LocalPath): Boolean = super.exists(resolve(path))

        override suspend fun move(source: LocalPath, destination: LocalPath): MoveOutcome {
            val existing = resolve(destination)
            if (existing != source && files.containsKey(existing.path)) {
                return MoveOutcome.NotSupported("Destination already exists: ${destination.path}")
            }
            return super.move(source, destination)
        }

        override suspend fun existsStrict(path: LocalPath): Existence = when {
            existenceUnknown(path.path) -> Existence.UNKNOWN
            staleAlias(path.path) -> Existence.PRESENT
            files.keys.any { it.equals(path.path, ignoreCase = true) } -> Existence.PRESENT
            else -> Existence.ABSENT
        }
    }

    /** Routed source ops that may not list the parent, like a delete route for a storage root. */
    private class UnlistableOps(
        private val delegate: FileSystemOps<LocalPath, LocalPathLookup>,
    ) : FileSystemOps<LocalPath, LocalPathLookup> by delegate {
        override suspend fun listFiles(path: LocalPath): List<LocalPath> = throw SecurityException("No route")
    }

    private val issues = mutableListOf<PathActionIssue>()

    private val skipAll: suspend (PathActionIssue) -> PathActionIssue.Resolution = { issue ->
        issues.add(issue)
        when (issue) {
            is PathActionIssue.PathAlreadyExists -> PathActionIssue.PathAlreadyExists.Resolution.Skip()
            is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
            else -> throw IllegalStateException("Unexpected issue: $issue")
        }
    }

    private fun MockFileSystemOps<LocalPath, LocalPathLookup>.rename(
        from: String,
        to: String,
        sourceOps: FileSystemOps<LocalPath, LocalPathLookup> = this,
    ) = setOf(LocalPath.build(from)).moveGeneric(
            destination = LocalPath.build(to),
            sourceOps = sourceOps,
            destOps = this,
            strategy = GenericCrossTypeMoveStrategy(),
            options = TransferStrategy.Options(attemptAtomicMove = false),
            onIssue = skipAll,
        )

    private fun MockFileSystemOps<LocalPath, LocalPathLookup>.asideKeys() = files.keys.filter { it.endsWith(".rename") }

    @Test
    fun `file rename that only changes case renames without a conflict`() = runTest {
        val ops = CaseInsensitiveOps().apply { addMockFile("/dir/a.txt", "content".toByteArray()) }

        val result = ops.rename("/dir/a.txt", "/dir/A.txt").last()

        result.shouldBeInstanceOf<MoveAction.State.Completed<*, *, *, *>>()
        result.movedFiles shouldHaveSize 1
        result.bytesMoved shouldBe 7L
        issues.shouldBeEmpty()
        ops.files.keys.filter { it.startsWith("/dir/") } shouldBe listOf("/dir/A.txt")
        ops.files["/dir/A.txt"]!!.content.decodeToString() shouldBe "content"
        ops.files["/dir"]!!.children shouldBe mutableListOf("A.txt")
    }

    @Test
    fun `folder rename that only changes case does not move the folder into itself`() = runTest {
        val ops = CaseInsensitiveOps().apply { addMockDir("/dir/photos") }

        ops.rename("/dir/photos", "/dir/Photos").last()

        issues.shouldBeEmpty()
        ops.files["/dir/Photos"]!!.type shouldBe FileType.DIRECTORY
        ops.files.keys.filter { it.startsWith("/dir/") } shouldBe listOf("/dir/Photos")
        ops.files["/dir"]!!.children shouldBe mutableListOf("Photos")
    }

    @Test
    fun `two real entries that differ only in case still conflict`() = runTest {
        val ops = MockFileSystemOps(lookupFactory).apply {
            addMockFile("/dir/a.txt", "lower".toByteArray())
            addMockFile("/dir/A.txt", "upper".toByteArray())
        }

        ops.rename("/dir/a.txt", "/dir/A.txt").last()

        issues.single().shouldBeInstanceOf<PathActionIssue.PathAlreadyExists>()
        ops.files["/dir/a.txt"]!!.content.decodeToString() shouldBe "lower"
        ops.files["/dir/A.txt"]!!.content.decodeToString() shouldBe "upper"
        ops.asideKeys().shouldBeEmpty()
    }

    @Test
    fun `listing goes through the destination side`() = runTest {
        val ops = CaseInsensitiveOps().apply { addMockFile("/dir/a.txt", "content".toByteArray()) }

        ops.rename("/dir/a.txt", "/dir/A.txt", sourceOps = UnlistableOps(ops)).last()

        issues.shouldBeEmpty()
        ops.files.keys.filter { it.startsWith("/dir/") } shouldBe listOf("/dir/A.txt")
    }

    @Test
    fun `refused first step fails instead of moving into itself`() = runTest {
        val ops = CaseInsensitiveOps().apply {
            addMockDir("/dir/photos")
            setMoveNotSupported { _, destination -> destination.endsWith(".rename") }
        }

        val error = shouldThrow<WriteException> { ops.rename("/dir/photos", "/dir/Photos").collect() }

        error.shouldNotBeInstanceOf<DataKeptException>()
        ops.files.keys.filter { it.startsWith("/dir/") } shouldBe listOf("/dir/photos")
    }

    @Test
    fun `first step that throws without renaming rethrows`() = runTest {
        val ops = CaseInsensitiveOps().apply {
            addMockFile("/dir/a.txt", "content".toByteArray())
            setMoveFailBeforeMutation { source, _ -> source == "/dir/a.txt" }
        }

        shouldThrow<IOException> { ops.rename("/dir/a.txt", "/dir/A.txt").collect() }

        ops.files["/dir/a.txt"]!!.content.decodeToString() shouldBe "content"
        ops.asideKeys().shouldBeEmpty()
    }

    @Test
    fun `first step that throws after renaming still completes`() = runTest {
        val ops = CaseInsensitiveOps().apply {
            addMockFile("/dir/a.txt", "content".toByteArray())
            setMoveFailAfterMutation { source, _ -> source == "/dir/a.txt" }
        }

        ops.rename("/dir/a.txt", "/dir/A.txt").last()
            .shouldBeInstanceOf<MoveAction.State.Completed<*, *, *, *>>().movedFiles shouldHaveSize 1

        ops.files["/dir/A.txt"]!!.content.decodeToString() shouldBe "content"
        ops.asideKeys().shouldBeEmpty()
    }

    @Test
    fun `first step with an unknown outcome names both possible names`() = runTest {
        val ops = CaseInsensitiveOps().apply {
            addMockFile("/dir/a.txt", "content".toByteArray())
            setMoveFailAfterMutation { source, _ -> source == "/dir/a.txt" }
            existenceUnknown = { it.endsWith(".rename") }
        }

        val error = shouldThrow<DataKeptException> { ops.rename("/dir/a.txt", "/dir/A.txt").collect() }

        val aside = ops.asideKeys().single()
        val recovery = error.recovery.shouldBeInstanceOf<DataKeptException.Recovery.Uncertain>()
        recovery.original shouldBe null
        aside shouldBe "/dir/${recovery.newData}"
    }

    @Test
    fun `second step refused puts the source back`() = runTest {
        var attempts = 0
        val ops = CaseInsensitiveOps().apply {
            addMockFile("/dir/a.txt", "content".toByteArray())
            setMoveNotSupported { source, destination ->
                (source.endsWith(".rename") && destination == "/dir/A.txt").also { if (it) attempts++ }
            }
        }

        val error = shouldThrow<WriteException> { ops.rename("/dir/a.txt", "/dir/A.txt").collect() }

        error.shouldNotBeInstanceOf<DataKeptException>()
        attempts shouldBe 1
        ops.files["/dir/a.txt"]!!.content.decodeToString() shouldBe "content"
        ops.asideKeys().shouldBeEmpty()
    }

    @Test
    fun `second step refused over a stale alias retries until it goes through`() = runTest {
        var attempts = 0
        val ops = CaseInsensitiveOps().apply {
            addMockFile("/dir/a.txt", "content".toByteArray())
            staleAlias = { it == "/dir/A.txt" }
            setMoveNotSupported(reason = "Destination already exists: /dir/A.txt") { source, destination ->
                source.endsWith(".rename") && destination == "/dir/A.txt" && ++attempts <= 2
            }
        }

        ops.rename("/dir/a.txt", "/dir/A.txt").last()
            .shouldBeInstanceOf<MoveAction.State.Completed<*, *, *, *>>().movedFiles shouldHaveSize 1

        attempts shouldBe 3
        ops.files.keys.filter { it.startsWith("/dir/") } shouldBe listOf("/dir/A.txt")
        ops.files["/dir/A.txt"]!!.content.decodeToString() shouldBe "content"
    }

    @Test
    fun `stale alias that outlasts the retries puts the source back`() = runTest {
        var attempts = 0
        val ops = CaseInsensitiveOps().apply {
            addMockFile("/dir/a.txt", "content".toByteArray())
            staleAlias = { it == "/dir/A.txt" }
            setMoveNotSupported(reason = "Destination already exists: /dir/A.txt") { source, destination ->
                (source.endsWith(".rename") && destination == "/dir/A.txt").also { if (it) attempts++ }
            }
        }

        val error = shouldThrow<WriteException> { ops.rename("/dir/a.txt", "/dir/A.txt").collect() }

        error.shouldNotBeInstanceOf<DataKeptException>()
        attempts shouldBe 7
        ops.files["/dir/a.txt"]!!.content.decodeToString() shouldBe "content"
        ops.asideKeys().shouldBeEmpty()
    }

    @Test
    fun `destination created by someone else during the rename is not retried`() = runTest {
        var attempts = 0
        val ops = CaseInsensitiveOps()
        ops.addMockFile("/dir/a.txt", "content".toByteArray())
        ops.setMoveNotSupported(reason = "Destination already exists: /dir/A.txt") { source, destination ->
            (source.endsWith(".rename") && destination == "/dir/A.txt").also {
                if (it && attempts++ == 0) ops.addMockFile("/dir/A.txt", "other".toByteArray())
            }
        }

        val error = shouldThrow<DataKeptException> { ops.rename("/dir/a.txt", "/dir/A.txt").collect() }

        error.recovery.shouldBeInstanceOf<DataKeptException.Recovery.Intermediate>()
        attempts shouldBe 1
        ops.files["/dir/A.txt"]!!.content.decodeToString() shouldBe "other"
        ops.files[ops.asideKeys().single()]!!.content.decodeToString() shouldBe "content"
    }

    @Test
    fun `stale alias the listing cannot confirm is not retried`() = runTest {
        var attempts = 0
        val ops = CaseInsensitiveOps()
        ops.addMockFile("/dir/a.txt", "content".toByteArray())
        ops.staleAlias = { it == "/dir/A.txt" }
        ops.setMoveNotSupported(reason = "Destination already exists: /dir/A.txt") { source, destination ->
            (source.endsWith(".rename") && destination == "/dir/A.txt").also {
                if (it && attempts++ == 0) ops.setFailListFiles(1)
            }
        }

        val error = shouldThrow<WriteException> { ops.rename("/dir/a.txt", "/dir/A.txt").collect() }

        error.shouldNotBeInstanceOf<DataKeptException>()
        attempts shouldBe 1
        ops.files["/dir/a.txt"]!!.content.decodeToString() shouldBe "content"
        ops.asideKeys().shouldBeEmpty()
    }

    @Test
    fun `second step that throws after renaming counts as done`() = runTest {
        val ops = CaseInsensitiveOps().apply {
            addMockFile("/dir/a.txt", "content".toByteArray())
            setMoveFailAfterMutation { source, destination -> source.endsWith(".rename") && destination == "/dir/A.txt" }
        }

        ops.rename("/dir/a.txt", "/dir/A.txt").last()
            .shouldBeInstanceOf<MoveAction.State.Completed<*, *, *, *>>().movedFiles shouldHaveSize 1

        ops.files["/dir/A.txt"]!!.content.decodeToString() shouldBe "content"
        ops.asideKeys().shouldBeEmpty()
    }

    @Test
    fun `failed second step that cannot put the source back names the kept copy`() = runTest {
        val ops = CaseInsensitiveOps().apply {
            addMockFile("/dir/a.txt", "content".toByteArray())
            setMoveFailBeforeMutation { source, _ -> source.endsWith(".rename") }
        }

        val error = shouldThrow<DataKeptException> { ops.rename("/dir/a.txt", "/dir/A.txt").collect() }

        val aside = ops.asideKeys().single()
        val recovery = error.recovery.shouldBeInstanceOf<DataKeptException.Recovery.Intermediate>()
        aside shouldBe "/dir/${recovery.intermediate}"
        ops.files[aside]!!.content.decodeToString() shouldBe "content"
    }
}
