package eu.darken.butler.common.files.local.walkers

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.local.LocalGateway
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.files.operations.MockFileSystemOps
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import testhelpers.BaseTest
import java.nio.file.NoSuchFileException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/**
 * Shared coverage for [DirectLocalWalker] and [IndirectLocalWalker].
 *
 * Both walkers run the shared [LocalWalkerCore] traversal; the only difference is the data source
 * ([eu.darken.butler.common.files.FileSystemOps] vs [LocalGateway] with a routing [LocalGateway.Mode]).
 * The traversal matrix is therefore parameterized over [WalkerVariant]; [LocalGateway]-specific mode
 * threading is verified in its own (non-parameterized) test.
 *
 * Each variant is backed by an in-memory [MockFileSystemOps]. [DirectLocalWalker] takes the
 * [eu.darken.butler.common.files.FileSystemOps] interface, so the mock is passed directly;
 * [IndirectLocalWalker] still needs a mockk [LocalGateway] facade delegating to the backing store.
 */
class LocalWalkerTest : BaseTest() {

    enum class WalkerVariant { DIRECT, INDIRECT }

    private val lookupFactory:
        (LocalPath, FileType, Long?, Instant?, Permissions?, Ownership?, Instant?) -> LocalPathLookup =
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

    private lateinit var backing: MockFileSystemOps<LocalPath, LocalPathLookup>

    @BeforeEach
    fun setup() {
        backing = MockFileSystemOps(lookupFactory)
    }

    private fun gatewayOps(store: MockFileSystemOps<LocalPath, LocalPathLookup>): LocalGateway {
        val gw = mockk<LocalGateway>()
        coEvery {
            gw.lookup(any(), any(), any<LocalGateway.Mode>())
        } coAnswers { store.lookup(firstArg<LocalPath>(), secondArg<LookupOptions>()) }
        coEvery {
            gw.lookupFiles(any(), any(), any<LocalGateway.Mode>())
        } coAnswers { store.lookupFiles(firstArg<LocalPath>(), secondArg<LookupOptions>()) }
        return gw
    }

    private fun walker(
        variant: WalkerVariant,
        start: LocalPath,
        store: MockFileSystemOps<LocalPath, LocalPathLookup> = backing,
        onFilter: suspend (LocalPathLookup) -> Boolean = { true },
        onError: suspend (LocalPathLookup, Exception) -> Boolean = { _, _ -> true },
    ): Flow<LocalPathLookup> = when (variant) {
        WalkerVariant.DIRECT ->
            DirectLocalWalker(store, start, LookupOptions(), onFilter, onError)
        WalkerVariant.INDIRECT ->
            IndirectLocalWalker(gatewayOps(store), LocalGateway.Mode.AUTO, start, LookupOptions(), onFilter, onError)
    }

    private suspend fun Flow<LocalPathLookup>.collectPaths(): List<String> = toList().map { it.lookedUp.path }

    @ParameterizedTest
    @EnumSource(WalkerVariant::class)
    fun `start that is a file emits only itself`(variant: WalkerVariant) = runTest {
        backing.addMockFile("/root/file.txt", "x".toByteArray())

        walker(variant, LocalPath.build("/root/file.txt")).collectPaths() shouldContainExactly listOf("/root/file.txt")
        backing.listFilesCalls.shouldBeEmpty()
    }

    @ParameterizedTest
    @EnumSource(WalkerVariant::class)
    fun `empty directory emits nothing`(variant: WalkerVariant) = runTest {
        backing.addMockDir("/root")

        walker(variant, LocalPath.build("/root")).collectPaths().shouldBeEmpty()
        backing.listFilesCalls shouldContain "/root"
    }

    @ParameterizedTest
    @EnumSource(WalkerVariant::class)
    fun `flat directory emits all direct children`(variant: WalkerVariant) = runTest {
        backing.addMockFile("/root/a.txt", "a".toByteArray())
        backing.addMockFile("/root/b.txt", "b".toByteArray())
        backing.addMockDir("/root/sub")

        walker(variant, LocalPath.build("/root")).collectPaths() shouldContainExactlyInAnyOrder listOf(
            "/root/a.txt",
            "/root/b.txt",
            "/root/sub",
        )
    }

    @ParameterizedTest
    @EnumSource(WalkerVariant::class)
    fun `nested tree emits all descendants but not the start directory`(variant: WalkerVariant) = runTest {
        backing.addMockFile("/root/top.txt", "t".toByteArray())
        backing.addMockFile("/root/a/a1.txt", "a1".toByteArray())
        backing.addMockFile("/root/a/b/b1.txt", "b1".toByteArray())
        backing.addMockDir("/root/a/empty")

        walker(variant, LocalPath.build("/root")).collectPaths() shouldContainExactlyInAnyOrder listOf(
            "/root/top.txt",
            "/root/a",
            "/root/a/a1.txt",
            "/root/a/b",
            "/root/a/b/b1.txt",
            "/root/a/empty",
        )
    }

    @ParameterizedTest
    @EnumSource(WalkerVariant::class)
    fun `traversal is depth-first along a single branch`(variant: WalkerVariant) = runTest {
        // chain: /root -> a -> b -> c.txt
        backing.addMockFile("/root/a/b/c.txt", "c".toByteArray())

        walker(variant, LocalPath.build("/root")).collectPaths() shouldContainExactly listOf(
            "/root/a",
            "/root/a/b",
            "/root/a/b/c.txt",
        )
    }

    @ParameterizedTest
    @EnumSource(WalkerVariant::class)
    fun `sibling subtrees are visited last-in-first-out`(variant: WalkerVariant) = runTest {
        // children insertion order is [s1, s2]; the LIFO queue visits s2's subtree before s1's.
        backing.addMockFile("/root/s1/f1.txt", "1".toByteArray())
        backing.addMockFile("/root/s2/f2.txt", "2".toByteArray())

        walker(variant, LocalPath.build("/root")).collectPaths() shouldContainExactly listOf(
            "/root/s1",
            "/root/s2",
            "/root/s2/f2.txt",
            "/root/s1/f1.txt",
        )
    }

    @ParameterizedTest
    @EnumSource(WalkerVariant::class)
    fun `onFilter prunes a directory and its descendants`(variant: WalkerVariant) = runTest {
        backing.addMockFile("/root/keep/kf.txt", "k".toByteArray())
        backing.addMockFile("/root/skip/sf.txt", "s".toByteArray())
        backing.addMockFile("/root/top.txt", "t".toByteArray())

        walker(
            variant,
            LocalPath.build("/root"),
            onFilter = { it.lookedUp.name != "skip" },
        ).collectPaths() shouldContainExactlyInAnyOrder listOf(
            "/root/keep",
            "/root/keep/kf.txt",
            "/root/top.txt",
        )
    }

    @ParameterizedTest
    @EnumSource(WalkerVariant::class)
    fun `onFilter excludes a matching leaf file`(variant: WalkerVariant) = runTest {
        backing.addMockFile("/root/a.txt", "a".toByteArray())
        backing.addMockFile("/root/b.txt", "b".toByteArray())

        walker(
            variant,
            LocalPath.build("/root"),
            onFilter = { it.lookedUp.name != "b.txt" },
        ).collectPaths() shouldContainExactly listOf("/root/a.txt")
    }

    @ParameterizedTest
    @EnumSource(WalkerVariant::class)
    fun `onError default swallows a listing failure`(variant: WalkerVariant) = runTest {
        backing.addMockFile("/root/child.txt", "c".toByteArray())
        backing.setFailListFiles(1) // fails the first (root) listing

        walker(variant, LocalPath.build("/root")).collectPaths().shouldBeEmpty()
        backing.listFilesCalls shouldContain "/root"
    }

    @ParameterizedTest
    @EnumSource(WalkerVariant::class)
    fun `onError returning false rethrows the listing failure`(variant: WalkerVariant) = runTest {
        backing.addMockDir("/root")
        backing.setFailListFiles(1) { SecurityException("denied") }

        shouldThrow<SecurityException> {
            walker(variant, LocalPath.build("/root"), onError = { _, _ -> false }).collectPaths()
        }
    }

    @ParameterizedTest
    @EnumSource(WalkerVariant::class)
    fun `a failing subdirectory is skipped while siblings are still emitted`(variant: WalkerVariant) = runTest {
        val failing = object : MockFileSystemOps<LocalPath, LocalPathLookup>(lookupFactory) {
            override suspend fun listFiles(path: LocalPath): List<LocalPath> {
                if (path.path == "/root/bad") throw SecurityException("denied")
                return super.listFiles(path)
            }
        }
        failing.addMockFile("/root/good/gf.txt", "g".toByteArray())
        failing.addMockFile("/root/bad/bf.txt", "b".toByteArray())

        walker(variant, LocalPath.build("/root"), store = failing).collectPaths() shouldContainExactlyInAnyOrder listOf(
            "/root/good",
            "/root/good/gf.txt",
            "/root/bad", // emitted as a child of /root; only its own listing fails
        )
    }

    @ParameterizedTest
    @EnumSource(WalkerVariant::class)
    fun `start path that does not exist throws`(variant: WalkerVariant) = runTest {
        shouldThrow<NoSuchFileException> {
            walker(variant, LocalPath.build("/nope")).collectPaths()
        }
    }

    @ParameterizedTest
    @EnumSource(WalkerVariant::class)
    fun `a symlink inside a directory is emitted as a leaf and not followed`(variant: WalkerVariant) = runTest {
        backing.addMockSymlink("/root/link", "/target")
        backing.addMockDir("/target")
        backing.addMockFile("/target/inside.txt", "i".toByteArray())

        walker(variant, LocalPath.build("/root")).collectPaths() shouldContainExactly listOf("/root/link")
        backing.listFilesCalls.contains("/root/link") shouldBe false
    }

    // Note: the behavior when the *start* path itself is a symlink is intentionally not asserted here.
    // The walker lists any non-file start, and that listing is backend-dependent: production
    // LocalFileSystemOps.listFiles uses Files.newDirectoryStream, which follows a start symlink into its
    // target, whereas the mock rejects it. Only encountered (non-start) symlinks have a backend-stable
    // contract (emitted as a leaf, not followed), covered by the test above.

    @Test
    fun `indirect walker threads the configured mode through to the gateway`() = runTest {
        backing.addMockFile("/root/f.txt", "f".toByteArray())
        val gw = mockk<LocalGateway>()
        coEvery {
            gw.lookup(any(), any(), any<LocalGateway.Mode>())
        } coAnswers { backing.lookup(firstArg<LocalPath>(), secondArg<LookupOptions>()) }
        coEvery {
            gw.lookupFiles(any(), any(), any<LocalGateway.Mode>())
        } coAnswers { backing.lookupFiles(firstArg<LocalPath>(), secondArg<LookupOptions>()) }

        IndirectLocalWalker(gw, LocalGateway.Mode.ROOT, LocalPath.build("/root"), LookupOptions()).toList()

        coVerify { gw.lookup(any(), any(), LocalGateway.Mode.ROOT) }
        coVerify { gw.lookupFiles(any(), any(), LocalGateway.Mode.ROOT) }
    }

    @Test
    fun `indirect walker threads mode through canonicalize when following symlinks`() = runTest {
        backing.addMockDir("/root")
        backing.addMockSymlink("/root/link", "/target")
        backing.addMockDir("/target")
        val gw = mockk<LocalGateway>()
        coEvery { gw.lookup(any(), any(), any<LocalGateway.Mode>()) } coAnswers {
            backing.lookup(firstArg<LocalPath>(), secondArg<LookupOptions>())
        }
        coEvery { gw.lookupFiles(any(), any(), any<LocalGateway.Mode>()) } coAnswers {
            backing.lookupFiles(firstArg<LocalPath>(), secondArg<LookupOptions>())
        }
        coEvery { gw.canonicalize(any(), any<LocalGateway.Mode>()) } coAnswers {
            backing.canonicalize(firstArg<LocalPath>())
        }

        IndirectLocalWalker(gw, LocalGateway.Mode.ROOT, LocalPath.build("/root"), LookupOptions(), followSymlinks = true).toList()

        coVerify { gw.canonicalize(any(), LocalGateway.Mode.ROOT) }
    }

    @Test
    fun `indirect walker does not canonicalize when not following symlinks`() = runTest {
        backing.addMockDir("/root")
        backing.addMockSymlink("/root/link", "/target")
        backing.addMockDir("/target")
        val gw = gatewayOps(backing)

        IndirectLocalWalker(gw, LocalGateway.Mode.AUTO, LocalPath.build("/root"), LookupOptions(), followSymlinks = false).toList()

        coVerify(exactly = 0) { gw.canonicalize(any(), any<LocalGateway.Mode>()) }
    }

    @Test
    fun `walk flow propagates cancellation from canonicalize`() = runTest {
        backing.addMockDir("/root")
        backing.addMockSymlink("/root/link", "/target")
        backing.addMockDir("/target")
        val gw = mockk<LocalGateway>()
        coEvery { gw.lookup(any(), any(), any<LocalGateway.Mode>()) } coAnswers {
            backing.lookup(firstArg<LocalPath>(), secondArg<LookupOptions>())
        }
        coEvery { gw.lookupFiles(any(), any(), any<LocalGateway.Mode>()) } coAnswers {
            backing.lookupFiles(firstArg<LocalPath>(), secondArg<LookupOptions>())
        }
        // Succeed for the seed (start), cancel when resolving the child symlink.
        coEvery { gw.canonicalize(any(), any<LocalGateway.Mode>()) } coAnswers {
            val p = firstArg<LocalPath>()
            if (p.path == "/root/link") throw CancellationException("cancelled") else backing.canonicalize(p)
        }

        shouldThrow<CancellationException> {
            IndirectLocalWalker(gw, LocalGateway.Mode.AUTO, LocalPath.build("/root"), LookupOptions(), followSymlinks = true).toList()
        }
    }
}
