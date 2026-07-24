package eu.darken.butler.workspace.core.preview

import eu.darken.butler.common.BuildWrap
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.local.routing.LocalPathRoutingPolicy
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.storage.StorageEnvironment
import eu.darken.butler.workspace.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.operations.Operation
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.io.File
import java.io.IOException
import kotlin.time.Instant

class FolderPreviewResolverTest : BaseTest() {

    private val dir = LocalPath.build(File("/tmp/preview-test/media"))

    @Suppress("UNCHECKED_CAST")
    private fun fileLookup(
        name: String,
        mtime: Long? = 1000L,
        size: Long? = 42L,
        fileType: FileType = FileType.FILE,
    ): APathLookup<APath<*>> = LocalPathLookup(
        lookedUp = dir.child(name),
        fileType = fileType,
        size = size,
        modifiedAt = mtime?.let { Instant.fromEpochMilliseconds(it) },
    ) as APathLookup<APath<*>>

    private fun create(
        scope: CoroutineScope,
        gatewaySwitch: GatewaySwitch = mockk(),
        hinter: FileSystemHinter = FileSystemHinter(),
        storageEnvironment: StorageEnvironment = mockk(),
        routingPolicy: LocalPathRoutingPolicy = mockk(),
    ) = FolderPreviewResolver(
        gatewaySwitch = gatewaySwitch,
        fileSystemHinter = hinter,
        appScope = scope,
        dispatcherProvider = TestDispatcherProvider(),
        workspaceSettings = mockk(),
        storageEnvironment = storageEnvironment,
        routingPolicy = routingPolicy,
    )

    @Test
    fun `restricted scoped-storage roots are skipped without gateway access`() = runTest {
        mockkObject(BuildWrap.VersionWrap)
        try {
            every { BuildWrap.VersionWrap.SDK_INT } returns 34
            val dataRoot = LocalPath.build(File("/storage/emulated/0/Android/data"))
            val obbRoot = LocalPath.build(File("/storage/emulated/0/Android/obb"))
            val storageEnv = mockk<StorageEnvironment> {
                every { publicDataDirs } returns listOf(dataRoot)
                every { publicObbDirs } returns listOf(obbRoot)
                every { ourPublicDirs } returns emptyList()
            }
            val policy = mockk<LocalPathRoutingPolicy> {
                every { aliasesOf(any()) } answers { setOf(firstArg()) }
            }
            val gateway = mockk<GatewaySwitch>()
            val resolver = create(
                backgroundScope,
                gatewaySwitch = gateway,
                storageEnvironment = storageEnv,
                routingPolicy = policy,
            )

            resolver.observe(dataRoot).first() shouldBe emptyList()
            resolver.observe(dataRoot.child("com.example.app")).first() shouldBe emptyList()
            resolver.observe(obbRoot.child("com.example.app")).first() shouldBe emptyList()

            coVerify(exactly = 0) { gateway.lookupFiles(any(), any()) }
        } finally {
            unmockkObject(BuildWrap.VersionWrap)
        }
    }

    @Test
    fun `our own scoped-storage dir keeps its previews despite the restricted-root skip`() = runTest {
        mockkObject(BuildWrap.VersionWrap)
        try {
            every { BuildWrap.VersionWrap.SDK_INT } returns 34
            val dataRoot = LocalPath.build(File("/storage/emulated/0/Android/data"))
            val ownDir = dataRoot.child("eu.darken.butler")
            val storageEnv = mockk<StorageEnvironment> {
                every { publicDataDirs } returns listOf(dataRoot)
                every { publicObbDirs } returns emptyList()
                every { ourPublicDirs } returns listOf(ownDir)
            }
            val policy = mockk<LocalPathRoutingPolicy> {
                every { aliasesOf(any()) } answers { setOf(firstArg()) }
            }
            val gateway = mockk<GatewaySwitch>()
            coEvery { gateway.lookupFiles(any(), any()) } returns listOf(fileLookup("pic.jpg"))
            val resolver = create(
                backgroundScope,
                gatewaySwitch = gateway,
                storageEnvironment = storageEnv,
                routingPolicy = policy,
            )

            resolver.observe(ownDir.child("files")).first().map { it.name } shouldBe listOf("pic.jpg")

            coVerify(exactly = 1) { gateway.lookupFiles(any(), any()) }
        } finally {
            unmockkObject(BuildWrap.VersionWrap)
        }
    }

    @Test
    fun `newest 4 media children are selected`() = runTest {
        val gateway = mockk<GatewaySwitch>()
        coEvery { gateway.lookupFiles(dir, any()) } returns listOf(
            fileLookup("old.jpg", mtime = 100L),
            fileLookup("newest.mp4", mtime = 900L),
            fileLookup("newer.png", mtime = 800L),
            fileLookup("doc.txt", mtime = 999L),
            fileLookup("subdir", fileType = FileType.DIRECTORY),
            fileLookup("mid1.webp", mtime = 500L),
            fileLookup("mid2.gif", mtime = 400L),
            fileLookup("empty.jpg", mtime = 950L, size = 0L),
            fileLookup("modern.heic", mtime = 700L),
        )
        val resolver = create(backgroundScope, gatewaySwitch = gateway)

        val children = resolver.observe(dir).first()

        children.map { it.name } shouldBe listOf("newest.mp4", "newer.png", "modern.heic", "mid1.webp")
    }

    @Test
    fun `null mtimes sort last with name tiebreak`() = runTest {
        val gateway = mockk<GatewaySwitch>()
        coEvery { gateway.lookupFiles(dir, any()) } returns listOf(
            fileLookup("b.jpg", mtime = null),
            fileLookup("a.jpg", mtime = null),
            fileLookup("stamped.jpg", mtime = 1L),
        )
        val resolver = create(backgroundScope, gatewaySwitch = gateway)

        val children = resolver.observe(dir).first()

        children.map { it.name } shouldBe listOf("stamped.jpg", "a.jpg", "b.jpg")
    }

    @Test
    fun `second observation is served from cache`() = runTest {
        val gateway = mockk<GatewaySwitch>()
        coEvery { gateway.lookupFiles(dir, any()) } returns listOf(fileLookup("pic.jpg"))
        val resolver = create(backgroundScope, gatewaySwitch = gateway)

        resolver.observe(dir).first()
        resolver.observe(dir).first()

        coVerify(exactly = 1) { gateway.lookupFiles(dir, any()) }
    }

    @Test
    fun `hinter event for child path re-resolves active observer`() = runTest {
        val gateway = mockk<GatewaySwitch>()
        val hinter = FileSystemHinter()
        coEvery { gateway.lookupFiles(dir, any()) } returnsMany listOf(
            listOf(fileLookup("first.jpg")),
            listOf(fileLookup("second.jpg")),
        )
        val resolver = create(backgroundScope, gatewaySwitch = gateway, hinter = hinter)

        val emissions = mutableListOf<List<String>>()
        val job = resolver.observe(dir)
            .onEach { emissions.add(it.map { child -> child.name }) }
            .launchIn(backgroundScope)
        runCurrent()

        hinter.trackPathsRemoved(mockk<Operation.Id>(), listOf(fileLookup("first.jpg")))
        runCurrent()
        job.cancel()

        emissions shouldBe listOf(listOf("first.jpg"), listOf("second.jpg"))
    }

    @Test
    fun `invalidateFor drops child entries`() = runTest {
        val gateway = mockk<GatewaySwitch>()
        coEvery { gateway.lookupFiles(dir, any()) } returns listOf(fileLookup("pic.jpg"))
        val resolver = create(backgroundScope, gatewaySwitch = gateway)

        resolver.observe(dir).first()
        resolver.invalidateFor(dir.parent!!)
        resolver.observe(dir).first()

        coVerify(exactly = 2) { gateway.lookupFiles(dir, any()) }
    }

    @Test
    fun `invalidateFor drops the directory's own entry`() = runTest {
        val gateway = mockk<GatewaySwitch>()
        coEvery { gateway.lookupFiles(dir, any()) } returns listOf(fileLookup("pic.jpg"))
        val resolver = create(backgroundScope, gatewaySwitch = gateway)

        resolver.observe(dir).first()
        resolver.invalidateFor(dir)
        resolver.observe(dir).first()

        coVerify(exactly = 2) { gateway.lookupFiles(dir, any()) }
    }

    @Test
    fun `invalidateDirs drops exactly the given directories`() = runTest {
        val gateway = mockk<GatewaySwitch>()
        val otherDir = LocalPath.build(File("/tmp/preview-test/other"))
        coEvery { gateway.lookupFiles(dir, any()) } returns listOf(fileLookup("pic.jpg"))
        coEvery { gateway.lookupFiles(otherDir, any()) } returns emptyList()
        val resolver = create(backgroundScope, gatewaySwitch = gateway)

        resolver.observe(dir).first()
        resolver.observe(otherDir).first()
        resolver.invalidateDirs(listOf(dir))
        resolver.observe(dir).first()
        resolver.observe(otherDir).first()

        coVerify(exactly = 2) { gateway.lookupFiles(dir, any()) }
        coVerify(exactly = 1) { gateway.lookupFiles(otherDir, any()) }
    }

    @Test
    fun `gateway failure resolves to empty and is cached`() = runTest {
        val gateway = mockk<GatewaySwitch>()
        coEvery { gateway.lookupFiles(dir, any()) } throws java.io.IOException("permission denied")
        val resolver = create(backgroundScope, gatewaySwitch = gateway)

        resolver.observe(dir).first() shouldBe emptyList()
        resolver.observe(dir).first() shouldBe emptyList()

        coVerify(exactly = 1) { gateway.lookupFiles(dir, any()) }
    }

    @Test
    fun `stale in-flight result is not cached after invalidation`() = runTest {
        val gateway = mockk<GatewaySwitch>()
        lateinit var resolver: FolderPreviewResolver
        coEvery { gateway.lookupFiles(dir, any()) } coAnswers {
            // Invalidation lands while the lookup is in flight, and its stamp record is
            // LRU-evicted before the lookup completes — the worst-case interleaving.
            resolver.invalidateFor(dir.parent!!)
            repeat(1100) { resolver.invalidateFor(LocalPath.build(File("/tmp/evict/$it"))) }
            listOf(fileLookup("stale.jpg"))
        } andThen listOf(fileLookup("fresh.jpg"))
        resolver = create(backgroundScope, gatewaySwitch = gateway)

        resolver.observe(dir).first().map { it.name } shouldBe listOf("stale.jpg")

        resolver.observe(dir).first().map { it.name } shouldBe listOf("fresh.jpg")
    }

    @Test
    fun `active observer recovers after in-flight invalidation`() = runTest {
        val gateway = mockk<GatewaySwitch>()
        lateinit var resolver: FolderPreviewResolver
        coEvery { gateway.lookupFiles(dir, any()) } coAnswers {
            resolver.invalidateFor(dir.parent!!)
            listOf(fileLookup("stale.jpg"))
        } andThen listOf(fileLookup("fresh.jpg"))
        resolver = create(backgroundScope, gatewaySwitch = gateway)

        val emissions = mutableListOf<List<String>>()
        val job = resolver.observe(dir)
            .onEach { emissions.add(it.map { child -> child.name }) }
            .launchIn(backgroundScope)
        runCurrent()
        job.cancel()

        emissions shouldBe listOf(listOf("stale.jpg"), listOf("fresh.jpg"))
    }

    @Test
    fun `cancellation is not cached as empty`() = runTest {
        val gateway = mockk<GatewaySwitch>()
        val entered = CompletableDeferred<Unit>()
        coEvery { gateway.lookupFiles(dir, any()) } coAnswers {
            entered.complete(Unit)
            throw CancellationException("cancelled")
        }
        val resolver = create(backgroundScope, gatewaySwitch = gateway)

        val job = launch { resolver.observe(dir).first() }
        entered.await()
        runCurrent()
        job.cancel()

        coEvery { gateway.lookupFiles(dir, any()) } returns listOf(fileLookup("pic.jpg"))
        resolver.observe(dir).first().map { it.name } shouldBe listOf("pic.jpg")
    }

    @Test
    fun `gateway-wrapped cancellation is not cached as empty`() = runTest {
        val gateway = mockk<GatewaySwitch>()
        coEvery { gateway.lookupFiles(dir, any()) } coAnswers {
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                // Mirrors SAF's ReadException wrapping the cancellation of ensureActive()
                throw IOException("gateway wrapped cancellation", e)
            }
        }
        val resolver = create(backgroundScope, gatewaySwitch = gateway)

        val job = launch { resolver.observe(dir).first() }
        runCurrent()
        job.cancel()
        runCurrent()

        coEvery { gateway.lookupFiles(dir, any()) } returns listOf(fileLookup("pic.jpg"))
        resolver.observe(dir).first().map { it.name } shouldBe listOf("pic.jpg")
    }
}
