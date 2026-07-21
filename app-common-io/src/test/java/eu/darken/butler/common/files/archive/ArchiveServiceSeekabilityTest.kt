package eu.darken.butler.common.files.archive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.lingala.zip4j.ZipFile
import okio.FileHandle
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
import java.io.IOException
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ArchiveServiceSeekabilityTest : BaseTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val dispatcherProvider = TestDispatcherProvider()
    private val passwordStore = ArchivePasswordStore()
    private val cacheDir = File(context.cacheDir, "archives")

    private lateinit var workDir: File
    private lateinit var gatewaySwitch: GatewaySwitch

    @Before
    fun setup() {
        workDir = File(context.cacheDir, "seekability_test").apply {
            deleteRecursively()
            mkdirs()
        }
        cacheDir.deleteRecursively()
        gatewaySwitch = mockk<GatewaySwitch>().apply {
            coEvery { openInputStream(any()) } answers {
                (firstArg<LocalPath>()).file.inputStream()
            }
            coEvery { lookup(any(), any<LookupOptions>()) } answers {
                @Suppress("UNCHECKED_CAST")
                firstArg<LocalPath>().file.toLookup() as APathLookup<APath<*>>
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

    private fun create() = ArchiveService(
        dispatcherProvider = dispatcherProvider,
        gatewaySwitchLazy = { gatewaySwitch },
        diskCache = ArchiveDiskCache(
            context = context,
            appScope = appScope,
            dispatcherProvider = dispatcherProvider,
        ),
        passwordStore = passwordStore,
    )

    private fun buildZip(name: String): LocalPath {
        val zipFile = File(workDir, name)
        ZipFile(zipFile).use { zip ->
            val src = File(workDir, "src.txt").apply { writeText("content") }
            zip.addFile(src)
        }
        return LocalPath.build(zipFile)
    }

    private fun buildTar(name: String): LocalPath {
        val tarFile = File(workDir, name)
        TarArchiveOutputStream(tarFile.outputStream()).use { tar ->
            val content = "tar content".toByteArray()
            val entry = TarArchiveEntry("data.txt").apply { size = content.size.toLong() }
            tar.putArchiveEntry(entry)
            tar.write(content)
            tar.closeArchiveEntry()
        }
        return LocalPath.build(tarFile)
    }

    /** Opens fine but fails every positioned read, like a pipe-backed SAF document. */
    private class StreamOnlyHandle : FileHandle(readWrite = false) {
        override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int =
            throw IOException("positioned reads not supported")

        override fun protectedSize(): Long = 100L
        override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) =
            throw IOException("read-only")

        override fun protectedResize(size: Long) = throw IOException("read-only")
        override fun protectedFlush() = Unit
        override fun protectedClose() = Unit
    }

    @Test
    fun `failed probe read on an opened handle means not seekable`() = runTest2 {
        coEvery { gatewaySwitch.file(any(), any()) } answers { StreamOnlyHandle() }
        val service = create()
        val container = buildZip("archive.zip")

        val e = shouldThrow<ArchiveNotSeekableException> { service.index(container) }

        e.container shouldBe container
        (cacheDir.listFiles() ?: emptyArray()).none { it.name.startsWith("container-") } shouldBe true
    }

    @Test
    fun `open failure propagates unchanged instead of masquerading as not seekable`() = runTest2 {
        val openError = ReadException("Permission denied by backend")
        coEvery { gatewaySwitch.file(any(), any()) } throws openError
        val service = create()
        val container = buildZip("archive.zip")

        val e = shouldThrow<ReadException> { service.index(container) }

        e.shouldNotBeInstanceOf<ArchiveNotSeekableException>()
        e shouldBe openError
    }

    @Test
    fun `tar indexing never needs the seekable handle`() = runTest2 {
        coEvery { gatewaySwitch.file(any(), any()) } throws ReadException("no random access")
        val service = create()
        val container = buildTar("archive.tar")

        val index = service.index(container)

        index.format shouldBe ArchiveFormat.TAR
        index.entriesBySegments[listOf("data.txt")].shouldBeInstanceOf<ArchiveEntryMeta>()
    }
}
