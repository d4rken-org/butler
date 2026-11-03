package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.SftpPath
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.CaseOnlyRenameException
import eu.darken.butler.common.files.errors.DataKeptException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.files.sftp.SftpPathLookup
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import testhelpers.BaseTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlin.uuid.Uuid

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

    private val location = Uuid.parse("11111111-2222-3333-4444-555555555555")

    private fun sftp(vararg segments: String) = SftpPath(location, segments.toList())

    private val sftpLookupFactory: (SftpPath, FileType, Long?, Instant?, Permissions?, Ownership?, Instant?) -> SftpPathLookup =
        { path, type, size, modifiedAt, _, _, _ ->
            SftpPathLookup(lookedUp = path, fileType = type, size = size, modifiedAt = modifiedAt)
        }

    /** [CaseInsensitiveOps] for an SFTP server whose file system matches names case-insensitively. */
    private inner class CaseInsensitiveSftpOps : MockFileSystemOps<SftpPath, SftpPathLookup>(sftpLookupFactory) {
        var existence: (String) -> Existence? = { null }

        /** Lookups that fail, which a lookup with fallbackToUnknown reports as UNKNOWN, like SftpGateway. */
        var lookupFails: (String) -> Boolean = { false }

        private fun resolve(path: SftpPath): SftpPath {
            val match = files.keys.firstOrNull { it.equals(path.path, ignoreCase = true) } ?: return path
            return path.parent?.child(match.substringAfterLast('/')) ?: path
        }

        override suspend fun lookup(path: SftpPath, options: LookupOptions): SftpPathLookup {
            if (options.fallbackToUnknown && lookupFails(path.path)) {
                return SftpPathLookup(lookedUp = path, fileType = FileType.UNKNOWN, size = null, modifiedAt = null)
            }
            return super.lookup(resolve(path), options)
        }

        override suspend fun exists(path: SftpPath): Boolean = super.exists(resolve(path))

        override suspend fun move(source: SftpPath, destination: SftpPath): MoveOutcome {
            val existing = resolve(destination)
            if (existing != source && files.containsKey(existing.path)) {
                return MoveOutcome.NotSupported("Destination already exists: ${destination.path}")
            }
            return super.move(source, destination)
        }

        override suspend fun existsStrict(path: SftpPath): Existence {
            existsStrictCalls.add(path.path)
            return existence(path.path) ?: when {
                files.keys.any { it.equals(path.path, ignoreCase = true) } -> Existence.PRESENT
                else -> Existence.ABSENT
            }
        }
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

    /** Lists everything but [path], like a folder whose route refuses a listing. */
    private inner class UnlistableFolderOps(private val folder: String) :
        MockFileSystemOps<LocalPath, LocalPathLookup>(lookupFactory) {
        override suspend fun listFiles(path: LocalPath): List<LocalPath> {
            if (path.path == folder) throw IOException("Cannot list $folder")
            return super.listFiles(path)
        }
    }

    private fun MockFileSystemOps<LocalPath, LocalPathLookup>.rename(
        from: String,
        to: String,
        sourceOps: FileSystemOps<LocalPath, LocalPathLookup> = this,
        exactTarget: Boolean = false,
    ) = setOf(LocalPath.build(from)).moveGeneric(
            destination = LocalPath.build(to),
            sourceOps = sourceOps,
            destOps = this,
            strategy = GenericCrossTypeMoveStrategy(),
            options = TransferStrategy.Options(attemptAtomicMove = false, exactTarget = exactTarget),
            onIssue = skipAll,
        )

    /** Everything a rename leaves behind, to compare an exact-target rename with a plain one. */
    private data class Outcome(val result: Any, val files: Map<String, Any>, val issues: List<Any?>)

    private suspend fun outcomeOf(
        exactTarget: Boolean,
        setup: () -> MockFileSystemOps<LocalPath, LocalPathLookup>,
        from: String,
        to: String,
    ): Outcome {
        issues.clear()
        val ops = setup()
        val result: Any = runCatching { ops.rename(from, to, exactTarget = exactTarget).last() }.fold(
            onSuccess = { state ->
                state as MoveAction.State.Completed<*, *, *, *>
                state.movedFiles.map { (source, destination) -> source.lookedUp.path to destination.lookedUp.path } to
                    state.skippedFiles.map { it.lookedUp.path }
            },
            onFailure = { it::class to it.message },
        )
        val files = ops.files.mapValues { (_, file) ->
            Triple(file.type, file.content.decodeToString(), file.children.toList())
        }
        val raised = issues.map { issue ->
            issue::class to (issue as? PathActionIssue.PathAlreadyExists)?.destination?.lookedUp?.path
        }
        return Outcome(result, files, raised)
    }

    private fun MockFileSystemOps<LocalPath, LocalPathLookup>.asideKeys() = files.keys.filter { it.endsWith(".rename") }

    private fun MockFileSystemOps<SftpPath, SftpPathLookup>.rename(from: SftpPath, to: SftpPath) =
        setOf(from).moveGeneric(
            destination = to,
            sourceOps = this,
            destOps = this,
            strategy = GenericCrossTypeMoveStrategy(),
            options = TransferStrategy.Options(attemptAtomicMove = false),
            onIssue = skipAll,
        )

    private fun MockFileSystemOps<*, *>.snapshot() = files.mapValues { (_, file) -> file.copy(children = file.children.toMutableList()) }

    /** A refused rename: no prompt, nothing moved, deleted, created or written, no hidden sibling left behind. */
    private fun MockFileSystemOps<*, *>.shouldBeUntouched(before: Map<String, MockFileSystemOps.MockFile>) {
        issues.shouldBeEmpty()
        moveCalls.shouldBeEmpty()
        deleteCalls.shouldBeEmpty()
        createDirCalls.shouldBeEmpty()
        createFileCalls.shouldBeEmpty()
        openOutputStreamCalls.shouldBeEmpty()
        files shouldBe before
        files.keys.filter { it.endsWith(".rename") }.shouldBeEmpty()
    }

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

    @Test
    fun `exactTarget on a file that only changes case behaves like a plain rename`() = runTest {
        // The listing fails, so both renames stop before touching anything
        val setup = {
            CaseInsensitiveOps().apply {
                addMockFile("/dir/a.txt", "content".toByteArray())
                setFailListFiles(1)
            }
        }

        val exact = outcomeOf(exactTarget = true, setup, "/dir/a.txt", "/dir/A.txt")

        exact shouldBe outcomeOf(exactTarget = false, setup, "/dir/a.txt", "/dir/A.txt")
    }

    @Test
    fun `exactTarget on a folder that only changes case behaves like a plain rename`() = runTest {
        val setup = {
            CaseInsensitiveOps().apply {
                addMockFile("/dir/photos/x.txt", "x".toByteArray())
                setFailListFiles(1)
            }
        }

        val exact = outcomeOf(exactTarget = true, setup, "/dir/photos", "/dir/Photos")

        exact shouldBe outcomeOf(exactTarget = false, setup, "/dir/photos", "/dir/Photos")
    }

    @Test
    fun `exactTarget behaves like a plain rename when the folder cannot be listed`() = runTest {
        val setup = {
            UnlistableFolderOps("/dir").apply {
                addMockFile("/dir/photos/x.txt", "x".toByteArray())
                addMockFile("/dir/Photos/y.txt", "y".toByteArray())
            }
        }

        val exact = outcomeOf(exactTarget = true, setup, "/dir/photos", "/dir/Photos")

        exact shouldBe outcomeOf(exactTarget = false, setup, "/dir/photos", "/dir/Photos")
        exact.files.keys shouldContain "/dir/photos/x.txt"
        exact.files.keys shouldContain "/dir/Photos/y.txt"
        exact.files.keys shouldNotContain "/dir/Photos/photos/x.txt"
        exact.issues.shouldBeEmpty()
    }

    @Test
    fun `exactTarget conflicts with a real entry that differs only in case`() = runTest {
        val ops = MockFileSystemOps(lookupFactory).apply {
            addMockFile("/dir/photos/x.txt", "x".toByteArray())
            addMockFile("/dir/Photos/y.txt", "y".toByteArray())
        }

        ops.rename("/dir/photos", "/dir/Photos", exactTarget = true).last()

        val issue = issues.single().shouldBeInstanceOf<PathActionIssue.PathAlreadyExists>()
        issue.destination.lookedUp shouldBe LocalPath.build("/dir/Photos")
        ops.files["/dir"]!!.children shouldBe mutableListOf("photos", "Photos")
        ops.files["/dir/photos"]!!.children shouldBe mutableListOf("x.txt")
        ops.files["/dir/Photos"]!!.children shouldBe mutableListOf("y.txt")
    }

    @Test
    fun `non-local file rename that only changes case stops before touching anything`() = runTest {
        val ops = CaseInsensitiveSftpOps().apply { addMockFile(sftp("dir", "a.txt").path, "content".toByteArray()) }
        val before = ops.snapshot()

        val error = shouldThrow<CaseOnlyRenameException> {
            ops.rename(sftp("dir", "a.txt"), sftp("dir", "A.txt")).collect()
        }

        error.path shouldBe sftp("dir", "a.txt")
        error.sourceName shouldBe "a.txt"
        error.destinationName shouldBe "A.txt"
        ops.shouldBeUntouched(before)
    }

    @Test
    fun `non-local folder rename that only changes case is not moved into itself`() = runTest {
        val ops = CaseInsensitiveSftpOps().apply { addMockFile(sftp("dir", "photos", "x.jpg").path, "jpg".toByteArray()) }
        val before = ops.snapshot()

        shouldThrow<CaseOnlyRenameException> { ops.rename(sftp("dir", "photos"), sftp("dir", "Photos")).collect() }

        ops.shouldBeUntouched(before)
        ops.files[sftp("dir", "photos").path]!!.children shouldBe mutableListOf("x.jpg")
    }

    @Test
    fun `non-local entries that differ only in case still conflict`() = runTest {
        val ops = MockFileSystemOps(sftpLookupFactory).apply {
            addMockFile(sftp("dir", "a.txt").path, "lower".toByteArray())
            addMockFile(sftp("dir", "A.txt").path, "upper".toByteArray())
        }

        ops.rename(sftp("dir", "a.txt"), sftp("dir", "A.txt")).last()

        issues.single().shouldBeInstanceOf<PathActionIssue.PathAlreadyExists>()
        ops.files[sftp("dir", "a.txt").path]!!.content.decodeToString() shouldBe "lower"
        ops.files[sftp("dir", "A.txt").path]!!.content.decodeToString() shouldBe "upper"
    }

    @Test
    fun `non-local case-sensitive storage renames a case variant like any other name`() = runTest {
        val ops = MockFileSystemOps(sftpLookupFactory).apply {
            addMockFile(sftp("dir", "a.txt").path, "content".toByteArray())
            existsStrictAnswers[sftp("dir", "A.txt").path] = Existence.ABSENT
        }

        val result = ops.rename(sftp("dir", "a.txt"), sftp("dir", "A.txt")).last()

        result.shouldBeInstanceOf<MoveAction.State.Completed<*, *, *, *>>().movedFiles shouldHaveSize 1
        issues.shouldBeEmpty()
        ops.files[sftp("dir", "A.txt").path]!!.content.decodeToString() shouldBe "content"
        ops.hasFile(sftp("dir", "a.txt").path) shouldBe false
    }

    @Test
    fun `case-only rename whose listing holds neither spelling stops`() = runTest {
        val local = CaseInsensitiveOps().apply {
            addMockFile("/dir/a.txt", "content".toByteArray())
            files["/dir"]!!.children.clear()
        }
        val localBefore = local.snapshot()

        shouldThrow<CaseOnlyRenameException> { local.rename("/dir/a.txt", "/dir/A.txt").collect() }

        local.shouldBeUntouched(localBefore)

        val remote = CaseInsensitiveSftpOps().apply {
            addMockFile(sftp("dir", "a.txt").path, "content".toByteArray())
            files[sftp("dir").path]!!.children.clear()
        }
        val remoteBefore = remote.snapshot()

        shouldThrow<CaseOnlyRenameException> { remote.rename(sftp("dir", "a.txt"), sftp("dir", "A.txt")).collect() }

        remote.shouldBeUntouched(remoteBefore)
    }

    @Test
    fun `local case-only rename onto a listing that holds only the new spelling stops`() = runTest {
        val ops = CaseInsensitiveOps().apply {
            addMockFile("/dir/A.txt", "content".toByteArray())
            addMockFile("/dir/Photos/x.jpg", "jpg".toByteArray())
        }
        val before = ops.snapshot()

        shouldThrow<CaseOnlyRenameException> { ops.rename("/dir/a.txt", "/dir/A.txt").collect() }
        shouldThrow<CaseOnlyRenameException> { ops.rename("/dir/photos", "/dir/Photos").collect() }

        ops.shouldBeUntouched(before)
    }

    @Test
    fun `non-local case-only rename onto a listing that holds only the new spelling stops`() = runTest {
        val ops = CaseInsensitiveSftpOps().apply {
            addMockFile(sftp("dir", "A.txt").path, "content".toByteArray())
            addMockFile(sftp("dir", "Photos", "x.jpg").path, "jpg".toByteArray())
        }
        val before = ops.snapshot()

        shouldThrow<CaseOnlyRenameException> { ops.rename(sftp("dir", "a.txt"), sftp("dir", "A.txt")).collect() }
        shouldThrow<CaseOnlyRenameException> { ops.rename(sftp("dir", "photos"), sftp("dir", "Photos")).collect() }

        ops.shouldBeUntouched(before)
    }

    @Test
    fun `non-local case-only rename with a failed lookup but a present destination stops`() = runTest {
        val ops = CaseInsensitiveSftpOps().apply {
            addMockFile(sftp("dir", "a.txt").path, "content".toByteArray())
            lookupFails = { it == sftp("dir", "A.txt").path }
        }
        val before = ops.snapshot()

        shouldThrow<CaseOnlyRenameException> { ops.rename(sftp("dir", "a.txt"), sftp("dir", "A.txt")).collect() }

        ops.existsStrictCalls shouldContain sftp("dir", "A.txt").path
        ops.listFilesCalls shouldContain sftp("dir").path
        ops.shouldBeUntouched(before)
    }

    @Test
    fun `case-only rename whose listing fails stops with the access error and its cause`() = runTest {
        val ops = CaseInsensitiveSftpOps().apply {
            addMockFile(sftp("dir", "a.txt").path, "content".toByteArray())
            setFailListFiles(1) { IOException("listing broke") }
        }
        val before = ops.snapshot()

        val error = shouldThrow<WriteException> { ops.rename(sftp("dir", "a.txt"), sftp("dir", "A.txt")).collect() }

        error.shouldNotBeInstanceOf<CaseOnlyRenameException>()
        error.path shouldBe sftp("dir", "a.txt")
        error.cause.shouldBeInstanceOf<IOException>().message shouldBe "listing broke"
        ops.shouldBeUntouched(before)
    }

    @Test
    fun `local case-only rename whose listing fails stops with the access error and its cause`() = runTest {
        val ops = CaseInsensitiveOps().apply {
            addMockFile("/dir/a.txt", "content".toByteArray())
            setFailListFiles(1) { IOException("listing broke") }
        }
        val before = ops.snapshot()

        val error = shouldThrow<WriteException> { ops.rename("/dir/a.txt", "/dir/A.txt").collect() }

        error.shouldNotBeInstanceOf<CaseOnlyRenameException>()
        error.path shouldBe LocalPath.build("/dir/a.txt")
        error.cause.shouldBeInstanceOf<IOException>().message shouldBe "listing broke"
        ops.shouldBeUntouched(before)
    }

    @Test
    fun `case-only rename whose destination existence is unknown stops with the access error`() = runTest {
        val ops = CaseInsensitiveSftpOps().apply {
            addMockFile(sftp("dir", "a.txt").path, "content".toByteArray())
            lookupFails = { it == sftp("dir", "A.txt").path }
            existence = { if (it == sftp("dir", "A.txt").path) Existence.UNKNOWN else null }
        }
        val before = ops.snapshot()

        val error = shouldThrow<WriteException> { ops.rename(sftp("dir", "a.txt"), sftp("dir", "A.txt")).collect() }

        error.shouldNotBeInstanceOf<CaseOnlyRenameException>()
        error.path shouldBe sftp("dir", "a.txt")
        error.cause shouldBe null
        ops.shouldBeUntouched(before)
        ops.listFilesCalls.shouldBeEmpty()
    }

    @Test
    fun `case-only rename whose existence check throws stops with the access error and its cause`() = runTest {
        val ops = CaseInsensitiveSftpOps().apply {
            addMockFile(sftp("dir", "a.txt").path, "content".toByteArray())
            lookupFails = { it == sftp("dir", "A.txt").path }
            existence = { if (it == sftp("dir", "A.txt").path) throw IOException("stat broke") else null }
        }
        val before = ops.snapshot()

        val error = shouldThrow<WriteException> { ops.rename(sftp("dir", "a.txt"), sftp("dir", "A.txt")).collect() }

        error.shouldNotBeInstanceOf<CaseOnlyRenameException>()
        error.path shouldBe sftp("dir", "a.txt")
        error.cause.shouldBeInstanceOf<IOException>().message shouldBe "stat broke"
        ops.existsStrictCalls shouldContain sftp("dir", "A.txt").path
        ops.shouldBeUntouched(before)
    }

    @Test
    fun `cancelled listing propagates as cancellation`() = runTest {
        val ops = CaseInsensitiveSftpOps().apply {
            addMockFile(sftp("dir", "a.txt").path, "content".toByteArray())
            setFailListFiles(1) { CancellationException("cancel case-only rename") }
        }
        val before = ops.snapshot()

        val error = shouldThrow<CancellationException> {
            ops.rename(sftp("dir", "a.txt"), sftp("dir", "A.txt")).collect()
        }

        error.shouldNotBeInstanceOf<CaseOnlyRenameException>()
        ops.shouldBeUntouched(before)
    }

    @Test
    fun `move of two sources onto a case variant of one is not a case-only rename`() = runTest {
        val ops = MockFileSystemOps(lookupFactory).apply {
            addMockFile("/dir/photos/x.jpg", "jpg".toByteArray())
            addMockFile("/dir/b.txt", "b".toByteArray())
            addMockDir("/dir/Photos")
        }

        val result = setOf(LocalPath.build("/dir/photos"), LocalPath.build("/dir/b.txt")).moveGeneric(
            destination = LocalPath.build("/dir/Photos"),
            sourceOps = ops,
            destOps = ops,
            strategy = GenericCrossTypeMoveStrategy(),
            options = TransferStrategy.Options(attemptAtomicMove = false),
            onIssue = skipAll,
        ).last()

        result.shouldBeInstanceOf<MoveAction.State.Completed<*, *, *, *>>()
        issues.shouldBeEmpty()
        ops.listFilesCalls shouldNotContain "/dir"
        ops.files["/dir/Photos/photos/x.jpg"]!!.content.decodeToString() shouldBe "jpg"
        ops.files["/dir/Photos/b.txt"]!!.content.decodeToString() shouldBe "b"
    }
}
