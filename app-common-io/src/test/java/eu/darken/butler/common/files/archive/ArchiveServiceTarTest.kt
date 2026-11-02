package eu.darken.butler.common.files.archive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.error.causeChain
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.MemoryLimitException
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tukaani.xz.LZMA2Options
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.coroutine.runTest2
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ArchiveServiceTarTest : BaseTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val dispatcherProvider = TestDispatcherProvider()
    private val passwordStore = ArchivePasswordStore()

    private lateinit var workDir: File
    private lateinit var gatewaySwitch: GatewaySwitch

    @Before
    fun setup() {
        workDir = File(context.cacheDir, "tar_test").apply {
            deleteRecursively()
            mkdirs()
        }
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

    private fun wrap(format: ArchiveFormat, raw: OutputStream): OutputStream = when (format) {
        ArchiveFormat.TAR -> raw
        ArchiveFormat.TAR_GZ -> GzipCompressorOutputStream(raw)
        ArchiveFormat.TAR_BZ2 -> BZip2CompressorOutputStream(raw)
        ArchiveFormat.TAR_XZ -> xz(raw)
        else -> error("Not a tar format: $format")
    }

    private fun xz(raw: OutputStream, preset: Int = LZMA2Options.PRESET_DEFAULT): OutputStream =
        XZCompressorOutputStream.builder().setOutputStream(raw).setLzma2Options(LZMA2Options(preset)).get()

    private fun xzBytes(data: ByteArray, from: Int, to: Int, preset: Int): ByteArray {
        val out = ByteArrayOutputStream()
        xz(out, preset).use { it.write(data, from, to - from) }
        return out.toByteArray()
    }

    /**
     * Writes [entries] as one tar byte sequence split at [splitAt], each part compressed as an
     * independent xz stream, and the two streams concatenated into one file.
     */
    private fun buildConcatenatedTarXz(
        entries: List<Pair<String, ByteArray>>,
        splitAt: Int,
        firstPreset: Int = LZMA2Options.PRESET_DEFAULT,
        secondPreset: Int = LZMA2Options.PRESET_DEFAULT,
    ): LocalPath {
        val tarOut = ByteArrayOutputStream()
        TarArchiveOutputStream(tarOut).use { tar ->
            entries.forEach { (name, content) ->
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = content.size.toLong() })
                tar.write(content)
                tar.closeArchiveEntry()
            }
        }
        val tar = tarOut.toByteArray()
        val file = File(workDir, "concatenated.tar.xz")
        file.writeBytes(xzBytes(tar, 0, splitAt, firstPreset) + xzBytes(tar, splitAt, tar.size, secondPreset))
        return LocalPath.build(file)
    }

    private fun xzHandler(decoderMemoryLimitKiB: Int) = TarArchiveHandler(
        ArchiveHandlerContext({ gatewaySwitch }, passwordStore, volumeFingerprint = { "test" }),
        decoderMemoryLimitKiB = decoderMemoryLimitKiB,
    )

    /** Writes [entries] in order; a name may repeat, as tar allows. */
    private fun buildTar(format: ArchiveFormat, entries: List<Pair<String, ByteArray>>): LocalPath {
        val file = File(workDir, "archive.${format.displayExtension}")
        TarArchiveOutputStream(wrap(format, file.outputStream())).use { tar ->
            entries.forEach { (name, content) ->
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = content.size.toLong() })
                tar.write(content)
                tar.closeArchiveEntry()
            }
        }
        return LocalPath.build(file)
    }

    private val tarFormats = listOf(ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2, ArchiveFormat.TAR_XZ)

    private val contents = listOf(
        "a.txt" to "alpha".toByteArray(),
        "dir/b.txt" to "bravo bravo".toByteArray(),
        "dir/sub/c.bin" to ByteArray(100_000) { (it % 251).toByte() },
    )

    private suspend fun ArchiveService.readEntry(container: APath<*>, name: String): ByteArray =
        openEntryStream(ArchivePath(container, name.split('/'))).use { it.readBytes() }

    private suspend fun ArchiveService.streamEntries(
        container: APath<*>,
        entries: Collection<ArchiveEntryMeta>,
        closeEach: Boolean = false,
    ): List<Pair<String, ByteArray>> {
        val delivered = mutableListOf<Pair<String, ByteArray>>()
        useEntryStreams(container, entries) { meta, stream ->
            delivered += meta.rawName to stream.readBytes()
            if (closeEach) stream.close()
        }
        return delivered
    }

    @Test
    fun `index lists every entry`() = runTest2 {
        tarFormats.forEach { format ->
            val container = buildTar(format, contents)
            val index = create().index(container)

            index.format shouldBe format
            index.entriesBySegments.values.filter { !it.isDirectory }.map { it.rawName }
                .shouldContainExactlyInAnyOrder(contents.map { it.first })
            contents.forEach { (name, content) ->
                index.entriesBySegments.getValue(name.split('/')).size shouldBe content.size.toLong()
            }
        }
    }

    @Test
    fun `openEntryStream returns each entry's exact bytes`() = runTest2 {
        tarFormats.forEach { format ->
            val container = buildTar(format, contents)
            val service = create()

            contents.forEach { (name, content) ->
                service.readEntry(container, name).toList() shouldBe content.toList()
            }
        }
    }

    @Test
    fun `useEntryStreams delivers each requested entry's exact bytes`() = runTest2 {
        tarFormats.forEach { format ->
            val container = buildTar(format, contents)
            val service = create()
            val metas = contents.map { (name, _) ->
                service.index(container).entriesBySegments.getValue(name.split('/'))
            }

            val delivered = service.streamEntries(container, metas)

            delivered.map { it.first } shouldContainExactly contents.map { it.first }
            delivered.zip(contents).forEach { (got, expected) ->
                got.second.toList() shouldBe expected.second.toList()
            }
        }
    }

    @Test
    fun `duplicate names resolve to the first occurrence everywhere`() = runTest2 {
        val first = "first occurrence".toByteArray()
        val second = "the second, longer occurrence".toByteArray()
        tarFormats.forEach { format ->
            val container = buildTar(
                format,
                listOf(
                    "dup.txt" to first,
                    "other.txt" to "other".toByteArray(),
                    "dup.txt" to second,
                ),
            )
            val service = create()
            val index = service.index(container)
            val dup = index.entriesBySegments.getValue(listOf("dup.txt"))

            index.entriesBySegments.values.count { it.rawName == "dup.txt" } shouldBe 1
            dup.size shouldBe first.size.toLong()
            service.readEntry(container, "dup.txt").toList() shouldBe first.toList()

            val delivered = service.streamEntries(container, listOf(dup))
            delivered.map { it.first } shouldContainExactly listOf("dup.txt")
            delivered.single().second.toList() shouldBe first.toList()
        }
    }

    @Test
    fun `useEntryStreams delivers in archive order regardless of request order`() = runTest2 {
        tarFormats.forEach { format ->
            val container = buildTar(format, contents)
            val service = create()
            val index = service.index(container)
            val reversed = contents.reversed().map { (name, _) -> index.entriesBySegments.getValue(name.split('/')) }

            val delivered = service.streamEntries(container, reversed)

            delivered.map { it.first } shouldContainExactly contents.map { it.first }
        }
    }

    @Test
    fun `closing a delivered stream does not break the next entry`() = runTest2 {
        tarFormats.forEach { format ->
            val container = buildTar(format, contents)
            val service = create()
            val index = service.index(container)
            val metas = contents.map { (name, _) -> index.entriesBySegments.getValue(name.split('/')) }

            val delivered = service.streamEntries(container, metas, closeEach = true)

            delivered.map { it.first } shouldContainExactly contents.map { it.first }
            delivered.zip(contents).forEach { (got, expected) ->
                got.second.toList() shouldBe expected.second.toList()
            }
        }
    }

    // Tar layout of [contents]: three 512-byte headers, each entry's data padded to 512 bytes, so
    // c.bin's data starts at 2560. Splitting there plus 50_000 lands mid-content.
    private val midContentSplit = 2560 + 50_000

    @Test
    fun `tar xz of concatenated streams reads as one archive`() = runTest2 {
        val container = buildConcatenatedTarXz(contents, splitAt = midContentSplit)
        val service = create()

        val index = service.index(container)
        index.entriesBySegments.values.filter { !it.isDirectory }.map { it.rawName }
            .shouldContainExactlyInAnyOrder(contents.map { it.first })
        contents.forEach { (name, content) ->
            index.entriesBySegments.getValue(name.split('/')).size shouldBe content.size.toLong()
            service.readEntry(container, name).toList() shouldBe content.toList()
        }

        val metas = contents.map { (name, _) -> index.entriesBySegments.getValue(name.split('/')) }
        val delivered = service.streamEntries(container, metas)
        delivered.map { it.first } shouldContainExactly contents.map { it.first }
        delivered.zip(contents).forEach { (got, expected) ->
            got.second.toList() shouldBe expected.second.toList()
        }
    }

    private fun ReadException.isMemoryLimit() =
        message!!.startsWith("Not enough memory to unpack this archive") &&
            causeChain.any { it is MemoryLimitException }

    @Test
    fun `tar xz dictionary over the decoder limit fails the scan with a ReadException`() = runTest2 {
        // wrap() writes xz's default preset 6, whose decoder needs ~8 MiB.
        val container = buildTar(ArchiveFormat.TAR_XZ, contents)

        shouldThrow<ReadException> {
            xzHandler(decoderMemoryLimitKiB = 2048).scan(container, ArchiveFormat.TAR_XZ)
        }.isMemoryLimit().shouldBeTrue()
    }

    @Test
    fun `tar xz dictionary over the decoder limit in a later stream fails the scan`() = runTest2 {
        val container = buildConcatenatedTarXz(contents, splitAt = midContentSplit, firstPreset = 1, secondPreset = 6)

        shouldThrow<ReadException> {
            xzHandler(decoderMemoryLimitKiB = 2048).scan(container, ArchiveFormat.TAR_XZ)
        }.isMemoryLimit().shouldBeTrue()
    }

    @Test
    fun `tar xz dictionary over the decoder limit fails entry reads past the stream boundary`() = runTest2 {
        val container = buildConcatenatedTarXz(contents, splitAt = midContentSplit, firstPreset = 1, secondPreset = 6)
        val index = create().index(container)
        val cBin = index.entriesBySegments.getValue(listOf("dir", "sub", "c.bin"))
        val handler = xzHandler(decoderMemoryLimitKiB = 2048)

        shouldThrow<ReadException> {
            handler.openEntry(container, index, cBin).use { it.readBytes() }
        }.isMemoryLimit().shouldBeTrue()

        shouldThrow<ReadException> {
            handler.useEntryStreams(container, index, listOf(cBin)) { _, stream -> stream.readBytes() }
        }.isMemoryLimit().shouldBeTrue()
    }
}
