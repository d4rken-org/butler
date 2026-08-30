package eu.darken.butler.common.files.archive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.errors.PathNotFoundException
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

/**
 * A container that cannot be read says nothing about the entry inside it, and indexing stats the
 * container first, so a deleted archive fails exactly like a corrupt one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ArchiveGatewayExistsStrictTest : BaseTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val dispatcherProvider = TestDispatcherProvider()
    private val cacheDir = File(context.cacheDir, "archives")

    private lateinit var workDir: File
    private lateinit var gatewaySwitch: GatewaySwitch

    @Before
    fun setup() {
        workDir = File(context.cacheDir, "archive_exists_test").apply {
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
            coEvery { existsStrict(any()) } answers {
                if (firstArg<LocalPath>().file.exists()) Existence.PRESENT else Existence.ABSENT
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

    private fun gateway() = ArchiveGateway(
        appScope = appScope,
        dispatcherProvider = dispatcherProvider,
        service = ArchiveService(
            dispatcherProvider = dispatcherProvider,
            gatewaySwitchLazy = { gatewaySwitch },
            diskCache = ArchiveDiskCache(
                context = context,
                appScope = appScope,
                dispatcherProvider = dispatcherProvider,
            ),
            passwordStore = ArchivePasswordStore(),
        ),
    )

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

    @Test
    fun `an entry in a readable container is answered from the index`() = runTest2 {
        val container = buildZip("readable.zip")

        gateway().existsStrict(ArchivePath(container, listOf("entry.txt"))) shouldBe Existence.PRESENT
        gateway().existsStrict(ArchivePath(container, listOf("nope.txt"))) shouldBe Existence.ABSENT
        gateway().existsStrict(ArchivePath(container, emptyList())) shouldBe Existence.PRESENT
    }

    @Test
    fun `a deleted container makes its root absent`() = runTest2 {
        val container = buildZip("deleted.zip")
        container.file.delete()

        gateway().existsStrict(ArchivePath(container, emptyList())) shouldBe Existence.ABSENT
    }

    @Test
    fun `a deleted container makes an entry inside it absent`() = runTest2 {
        val container = buildZip("deleted_entry.zip")
        container.file.delete()

        gateway().existsStrict(ArchivePath(container, listOf("entry.txt"))) shouldBe Existence.ABSENT
    }

    @Test
    fun `a corrupt container cannot answer for its entries`() = runTest2 {
        val container = buildZip("corrupt.zip")
        container.file.writeBytes("this is not a zip".toByteArray())

        gateway().existsStrict(ArchivePath(container, listOf("entry.txt"))) shouldBe Existence.UNKNOWN
    }

    /** Tar scanning propagates raw IOExceptions, which a ReadException-only catch would miss. */
    @Test
    fun `a corrupt tar cannot answer for its entries`() = runTest2 {
        val container = buildTar("corrupt.tar")
        container.file.writeBytes(ByteArray(2048) { 0x41 })

        gateway().existsStrict(ArchivePath(container, listOf("data.txt"))) shouldBe Existence.UNKNOWN
    }
}
