package eu.darken.butler.editor.core.sources

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.editor.core.engine.ContentSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okio.FileHandle
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.File
import java.io.IOException
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Metadata edge cases: short-reading sources feeding charset detection, and providers
 * reporting null size/mtime (real on SAF).
 */
class FileDataSourceMetadataTest : BaseTest() {

    private val workspaceId = Workspace.Id(Uuid.random())
    private val filePath = LocalPath.build(File("/tmp/meta-test", "test.txt"))

    /** Serves [bytes] but returns at most ONE byte per read call - a legal, maximally-short-reading source. */
    private class TrickleFileHandle(private val bytes: ByteArray) : FileHandle(false) {
        override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int {
            if (fileOffset >= bytes.size) return -1
            val n = minOf(1, byteCount, bytes.size - fileOffset.toInt())
            System.arraycopy(bytes, fileOffset.toInt(), array, arrayOffset, n)
            return n
        }

        override fun protectedSize(): Long = bytes.size.toLong()
        override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) =
            throw UnsupportedOperationException()

        override fun protectedResize(size: Long) = throw UnsupportedOperationException()
        override fun protectedFlush() = Unit
        override fun protectedClose() = Unit
    }

    private fun mockLookup(size: Long?, modifiedAt: Instant?): APathLookup<APath<*>> =
        mockk<APathLookup<APath<*>>>().apply {
            every { this@apply.size } returns size
            every { this@apply.modifiedAt } returns modifiedAt
        }

    private fun mockGateway(
        content: ByteArray,
        size: Long? = content.size.toLong(),
        modifiedAt: Instant? = Instant.fromEpochMilliseconds(1_000_000L),
    ): GatewaySwitch = mockk<GatewaySwitch>().apply {
        coEvery { exists(any()) } returns true
        coEvery { canWrite(any()) } returns true
        coEvery { lookup(any(), any()) } returns mockLookup(size, modifiedAt)
        coEvery { file(any(), any()) } answers { TrickleFileHandle(content) }
    }

    @Test
    fun `charset detection survives short-reading sources - UTF-16LE BOM`() = runTest {
        // A single read() returns 1 byte; detection must loop until the sample is full
        val content = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "Hello".flatMap { listOf(it.code.toByte(), 0.toByte()) }.toByteArray()
        val dataSource = FileDataSource(workspaceId, filePath, mockGateway(content))

        dataSource.open()

        val source = dataSource.contentSource.value as ContentSource.File
        source.detectedCharset shouldBe Charsets.UTF_16LE
        source.hasBOM shouldBe true
    }

    @Test
    fun `charset detection survives short-reading sources - multibyte UTF-8 without BOM`() = runTest {
        val content = "Grüße, 世界".toByteArray(Charsets.UTF_8)
        val dataSource = FileDataSource(workspaceId, filePath, mockGateway(content))

        dataSource.open()

        val source = dataSource.contentSource.value as ContentSource.File
        source.detectedCharset shouldBe Charsets.UTF_8
        source.hasBOM shouldBe false
    }

    @Test
    fun `open fails cleanly when the provider reports no size`() = runTest {
        val dataSource = FileDataSource(workspaceId, filePath, mockGateway("hello".toByteArray(), size = null))

        shouldThrow<IOException> { dataSource.open() }
    }

    @Test
    fun `open tolerates a missing modification time`() = runTest {
        val dataSource = FileDataSource(workspaceId, filePath, mockGateway("hello".toByteArray(), modifiedAt = null))

        dataSource.open()

        val source = dataSource.contentSource.value as ContentSource.File
        source.lastModified.shouldBeNull()
        dataSource.getMeta().modifiedAt.shouldBeNull()
        dataSource.getMeta().size shouldBe 5L
    }

    @Test
    fun `getMeta fails cleanly when the provider stops reporting size`() = runTest {
        val gateway = mockGateway("hello".toByteArray())
        val dataSource = FileDataSource(workspaceId, filePath, gateway)
        dataSource.open()

        coEvery { gateway.lookup(any(), any()) } returns mockLookup(size = null, modifiedAt = null)

        shouldThrow<IOException> { dataSource.getMeta() }
    }
}
