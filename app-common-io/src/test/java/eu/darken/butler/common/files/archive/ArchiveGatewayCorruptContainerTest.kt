package eu.darken.butler.common.files.archive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.error.localized
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.errors.PathException
import eu.darken.butler.common.files.errors.PathNotFoundException
import eu.darken.butler.common.files.errors.PathPermissionDeniedException
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import net.lingala.zip4j.ZipFile
import okio.Path.Companion.toOkioPath
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.coroutine.runTest2
import java.io.File
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ArchiveGatewayCorruptContainerTest : BaseTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val dispatcherProvider = TestDispatcherProvider()
    private val cacheDir = File(context.cacheDir, "archives")

    private lateinit var workDir: File
    private lateinit var gatewaySwitch: GatewaySwitch

    @Before
    fun setup() {
        workDir = File(context.cacheDir, "archive_corrupt_test").apply {
            deleteRecursively()
            mkdirs()
        }
        cacheDir.deleteRecursively()
        gatewaySwitch = mockk<GatewaySwitch>().apply {
            coEvery { openInputStream(any()) } answers { firstArg<LocalPath>().file.inputStream() }
            coEvery { file(any(), any()) } answers {
                okio.FileSystem.SYSTEM.openReadOnly(firstArg<LocalPath>().file.toOkioPath())
            }
            coEvery { lookup(any(), any<LookupOptions>()) } answers {
                val file = firstArg<LocalPath>().file
                if (!file.exists()) throw PathNotFoundException(firstArg<LocalPath>())
                @Suppress("UNCHECKED_CAST")
                file.toLookup() as APathLookup<APath<*>>
            }
        }
    }

    @After
    fun teardown() {
        appScope.cancel()
        workDir.deleteRecursively()
        cacheDir.deleteRecursively()
    }

    private fun File.toLookup() = LocalPathLookup(
        lookedUp = LocalPath.build(this),
        fileType = if (isDirectory) FileType.DIRECTORY else FileType.FILE,
        size = if (isFile) length() else null,
        modifiedAt = Instant.fromEpochMilliseconds(lastModified()),
    )

    private fun service() = ArchiveService(
        dispatcherProvider = dispatcherProvider,
        gatewaySwitchLazy = { gatewaySwitch },
        diskCache = ArchiveDiskCache(
            context = context,
            appScope = appScope,
            dispatcherProvider = dispatcherProvider,
        ),
        passwordStore = ArchivePasswordStore(),
    )

    private fun gateway(service: ArchiveService) = ArchiveGateway(
        appScope = appScope,
        dispatcherProvider = dispatcherProvider,
        service = service,
    )

    private fun gatewayOverFailingIndex(failure: Throwable): ArchiveGateway {
        val service = mockk<ArchiveService>()
        coEvery { service.index(any()) } throws failure
        return gateway(service)
    }

    private fun buildZip(name: String): LocalPath {
        val zipFile = File(workDir, name)
        ZipFile(zipFile).use { zip ->
            val src = File(workDir, "entry.txt").apply { writeText("content") }
            zip.addFile(src)
        }
        return LocalPath.build(zipFile)
    }

    private fun buildTar(name: String): LocalPath {
        val tarFile = File(workDir, name)
        TarArchiveOutputStream(tarFile.outputStream()).use { tar ->
            val content = "tar content".toByteArray()
            tar.putArchiveEntry(TarArchiveEntry("data.txt").apply { size = content.size.toLong() })
            tar.write(content)
            tar.closeArchiveEntry()
        }
        return LocalPath.build(tarFile)
    }

    private fun corruptZip(): LocalPath = buildZip("corrupt.zip").also {
        it.file.writeBytes("this is not a zip".toByteArray())
    }

    private fun corruptTar(): LocalPath = buildTar("corrupt.tar").also {
        it.file.writeBytes(ByteArray(2048) { 0x41 })
    }

    /** The raw parser failure the gateway has to wrap; a PathException here would test nothing. */
    private suspend fun rawIndexFailure(service: ArchiveService, container: LocalPath): Throwable {
        val failure = shouldThrowAny { service.index(container) }
        failure.shouldNotBeInstanceOf<PathException>()
        return failure
    }

    private suspend fun shouldWrap(rawFailure: Throwable, block: suspend () -> Any?): ReadException {
        val thrown = shouldThrow<ReadException> { block() }
        val cause = thrown.cause.shouldNotBeNull()
        cause.shouldNotBeInstanceOf<PathException>()
        cause::class shouldBe rawFailure::class
        return thrown
    }

    private suspend fun everyIndexedOperationWraps(container: LocalPath, entryName: String) {
        val service = service()
        val rawFailure = rawIndexFailure(service, container)
        val gateway = gateway(service)
        val root = ArchivePath(container, emptyList())
        val entry = ArchivePath(container, listOf(entryName))

        shouldWrap(rawFailure) { gateway.lookup(root, LookupOptions()) }
        shouldWrap(rawFailure) { gateway.lookup(entry, LookupOptions()) }
        shouldWrap(rawFailure) { gateway.lookupFiles(root, LookupOptions()) }
        shouldWrap(rawFailure) { gateway.du(root, APathGateway.DuOptions()) }
        shouldWrap(rawFailure) { gateway.walk(root, LookupOptions(), APathGateway.WalkOptions()).toList() }
        shouldWrap(rawFailure) { gateway.readSymbolicLink(entry) }
        gateway.exists(root) shouldBe false
        gateway.exists(entry) shouldBe false
    }

    @Test
    fun `a corrupt zip is reported as unreadable by every indexed operation`() = runTest2 {
        everyIndexedOperationWraps(corruptZip(), "entry.txt")
    }

    @Test
    fun `a corrupt tar is reported as unreadable by every indexed operation`() = runTest2 {
        everyIndexedOperationWraps(corruptTar(), "data.txt")
    }

    @Test
    fun `the localized error of a corrupt zip keeps the parser's reason`() = runTest2 {
        val container = corruptZip()
        val service = service()
        val rawFailure = rawIndexFailure(service, container)

        val thrown = shouldWrap(rawFailure) {
            gateway(service).lookup(ArchivePath(container, emptyList()), LookupOptions())
        }

        val reason = thrown.cause.shouldNotBeNull().message.shouldNotBeNull()
        thrown.localized(context).description.get(context) shouldContain reason
    }

    @Test
    fun `a deleted container is still reported as not found`() = runTest2 {
        val container = buildZip("deleted.zip")
        container.file.delete()

        shouldThrow<PathNotFoundException> {
            gateway(service()).lookup(ArchivePath(container, emptyList()), LookupOptions())
        }
    }

    @Test
    fun `path failures from indexing pass through unchanged`() = runTest2 {
        val container = LocalPath.build(File(workDir, "mocked.zip"))
        val path = ArchivePath(container, listOf("entry.txt"))
        listOf(
            ArchivePasswordRequiredException(container),
            PathPermissionDeniedException(container, "lookup", PathPermissionDeniedException.Reason.NO_MECHANISM),
            ReadException("unreadable", container),
            PathNotFoundException(container),
        ).forEach { failure ->
            shouldThrowAny {
                gatewayOverFailingIndex(failure).lookup(path, LookupOptions())
            } shouldBeSameInstanceAs failure
        }
    }

    @Test
    fun `exists answers false for read failures`() = runTest2 {
        val container = LocalPath.build(File(workDir, "mocked.zip"))
        val path = ArchivePath(container, listOf("entry.txt"))
        listOf(
            ArchivePasswordRequiredException(container),
            ReadException("unreadable", container),
            PathNotFoundException(container),
        ).forEach { failure ->
            gatewayOverFailingIndex(failure).exists(path) shouldBe false
        }
    }

    @Test
    fun `exists rethrows a permission denial`() = runTest2 {
        val container = LocalPath.build(File(workDir, "mocked.zip"))
        val failure = PathPermissionDeniedException(
            container,
            "lookup",
            PathPermissionDeniedException.Reason.NO_MECHANISM,
        )

        shouldThrowAny {
            gatewayOverFailingIndex(failure).exists(ArchivePath(container, listOf("entry.txt")))
        } shouldBeSameInstanceAs failure
    }

    @Test
    fun `cancellation during indexing is not wrapped`() = runTest2 {
        val container = LocalPath.build(File(workDir, "mocked.zip"))
        val path = ArchivePath(container, listOf("entry.txt"))
        val cancellation = CancellationException("cancelled")
        val gateway = gatewayOverFailingIndex(cancellation)

        shouldThrowAny { gateway.lookup(path, LookupOptions()) } shouldBeSameInstanceAs cancellation
        shouldThrowAny { gateway.exists(path) } shouldBeSameInstanceAs cancellation
    }
}
