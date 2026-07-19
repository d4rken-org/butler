package eu.darken.butler.common.files.archive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
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
class ArchiveServiceCompressTest : BaseTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val dispatcherProvider = TestDispatcherProvider()
    private val passwordStore = ArchivePasswordStore()

    private lateinit var workDir: File
    private lateinit var gatewaySwitch: GatewaySwitch

    /** When set, container lookups report this fixed stat regardless of the real file. */
    private var pinnedContainerStat: Pair<File, LocalPathLookup>? = null

    @Before
    fun setup() {
        workDir = File(context.cacheDir, "compress_test").apply {
            deleteRecursively()
            mkdirs()
        }
        gatewaySwitch = mockk<GatewaySwitch>().apply {
            coEvery { openInputStream(any()) } answers {
                (firstArg<LocalPath>()).file.inputStream()
            }
            coEvery { openOutputStream(any(), any()) } answers {
                (firstArg<LocalPath>()).file.outputStream()
            }
            coEvery { lookup(any(), any<LookupOptions>()) } answers {
                val path = firstArg<LocalPath>()
                @Suppress("UNCHECKED_CAST")
                (pinnedContainerStat
                    ?.takeIf { it.first == path.file }
                    ?.second
                    ?: path.file.toLookup()) as APathLookup<APath<*>>
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

    private fun sourceFile(name: String, content: String): Pair<ArchiveService.WriteEntry, File> {
        val file = File(workDir, name.replace('/', '_')).apply { writeText(content) }
        val entry = ArchiveService.WriteEntry(
            name = name,
            source = LocalPath.build(file),
            isDirectory = false,
            size = file.length(),
        )
        return entry to file
    }

    private fun dirEntry(name: String) = ArchiveService.WriteEntry(
        name = name,
        source = LocalPath.build(File(workDir, "irrelevant")),
        isDirectory = true,
        size = null,
    )

    private suspend fun ArchiveService.readEntryText(container: LocalPath, vararg segments: String): String =
        openEntryStream(ArchivePath(container, segments.toList())).use { it.readBytes().decodeToString() }

    @Test
    fun `plain zip round trip preserves entries and content`() = runTest2 {
        val service = create()
        val (fileA, _) = sourceFile("a.txt", "alpha content")
        val (fileB, _) = sourceFile("sub/b.txt", "beta content")
        val container = LocalPath.build(File(workDir, "out.zip"))

        val reported = mutableListOf<String>()
        service.compress(
            ArchiveWriteOptions(ArchiveFormat.ZIP),
            container,
            listOf(fileA, dirEntry("sub"), fileB),
        ) { entry, _ -> reported += entry.name }

        reported shouldContainExactlyInAnyOrder listOf("a.txt", "sub", "sub/b.txt")

        val index = service.index(container)
        index.isEncrypted shouldBe false
        index.entriesBySegments[listOf("a.txt")]?.isDirectory shouldBe false
        index.entriesBySegments[listOf("sub")]?.isDirectory shouldBe true
        service.readEntryText(container, "a.txt") shouldBe "alpha content"
        service.readEntryText(container, "sub", "b.txt") shouldBe "beta content"
    }

    @Test
    fun `encrypted zip is aes256 and readable via existing read path`() = runTest2 {
        val service = create()
        val (fileA, _) = sourceFile("secret.txt", "top secret data")
        val (fileB, _) = sourceFile("docs/readme.txt", "docs data")
        val container = LocalPath.build(File(workDir, "enc.zip"))
        val password = "hunter2".toCharArray()

        service.compress(
            ArchiveWriteOptions(ArchiveFormat.ZIP, CompressionPreset.NORMAL, password),
            container,
            listOf(fileA, dirEntry("docs"), fileB),
        ) { _, _ -> }

        // The service borrows the password, it must not wipe it (that's the caller's job).
        String(password) shouldBe "hunter2"

        // Every file entry is AES-256; directory entries stay unencrypted.
        net.lingala.zip4j.ZipFile(container.file).use { zip4j ->
            zip4j.fileHeaders.forEach { header ->
                if (header.isDirectory) {
                    header.isEncrypted shouldBe false
                } else {
                    header.isEncrypted.shouldBeTrue()
                    header.encryptionMethod shouldBe EncryptionMethod.AES
                    header.aesExtraDataRecord.aesKeyStrength shouldBe AesKeyStrength.KEY_STRENGTH_256
                }
            }
        }

        service.index(container).isEncrypted shouldBe true
        service.requiresPassword(container) shouldBe true
        service.verifyPassword(container, "wrong".toCharArray()) shouldBe false
        service.verifyPassword(container, "hunter2".toCharArray()) shouldBe true

        passwordStore.set(container, "hunter2".toCharArray())
        service.readEntryText(container, "secret.txt") shouldBe "top secret data"
        service.readEntryText(container, "docs", "readme.txt") shouldBe "docs data"
    }

    @Test
    fun `zip level presets order output size on a compressible corpus`() = runTest2 {
        val service = create()
        val corpus = buildString {
            repeat(4000) { append("line $it of highly repetitive corpus content for level testing\n") }
        }

        suspend fun sizeAt(preset: CompressionPreset): Long {
            val (entry, _) = sourceFile("corpus_$preset.txt", corpus)
            val container = LocalPath.build(File(workDir, "level_$preset.zip"))
            service.compress(ArchiveWriteOptions(ArchiveFormat.ZIP, preset), container, listOf(entry)) { _, _ -> }
            return container.file.length()
        }

        val fast = sizeAt(CompressionPreset.FAST)
        val normal = sizeAt(CompressionPreset.NORMAL)
        val best = sizeAt(CompressionPreset.BEST)

        best shouldBeLessThanOrEqualTo normal
        normal shouldBeLessThanOrEqualTo fast
        // Levels must actually differ, not just tie.
        (fast - best).toInt() shouldBeGreaterThan 0
    }

    @Test
    fun `tar gz level maps to deflate 1 and 9 via gzip xfl byte`() = runTest2 {
        val service = create()

        suspend fun xflAt(preset: CompressionPreset): Int {
            val (entry, _) = sourceFile("data_$preset.txt", "payload".repeat(100))
            val container = LocalPath.build(File(workDir, "xfl_$preset.tar.gz"))
            service.compress(ArchiveWriteOptions(ArchiveFormat.TAR_GZ, preset), container, listOf(entry)) { _, _ -> }
            // RFC 1952: XFL byte at offset 8 - 2 = max compression (9), 4 = fastest (1).
            return container.file.readBytes()[8].toInt()
        }

        xflAt(CompressionPreset.FAST) shouldBe 4
        xflAt(CompressionPreset.BEST) shouldBe 2
    }

    @Test
    fun `tar bz2 block size follows preset in stream header`() = runTest2 {
        val service = create()

        suspend fun headerAt(preset: CompressionPreset): String {
            val (entry, _) = sourceFile("data_$preset.txt", "payload".repeat(100))
            val container = LocalPath.build(File(workDir, "bs_$preset.tar.bz2"))
            service.compress(ArchiveWriteOptions(ArchiveFormat.TAR_BZ2, preset), container, listOf(entry)) { _, _ -> }
            return container.file.readBytes().copyOfRange(0, 4).decodeToString()
        }

        headerAt(CompressionPreset.FAST) shouldBe "BZh1"
        headerAt(CompressionPreset.NORMAL) shouldBe "BZh5"
        headerAt(CompressionPreset.BEST) shouldBe "BZh9"
    }

    @Test
    fun `tar round trip resolves unknown entry sizes at write time`() = runTest2 {
        val service = create()
        val (known, _) = sourceFile("known.txt", "known size")
        val (unknownEntry, _) = sourceFile("unknown.txt", "size resolved at write time")
        val unknownSize = unknownEntry.copy(size = null)
        val container = LocalPath.build(File(workDir, "out.tar"))

        service.compress(ArchiveWriteOptions(ArchiveFormat.TAR), container, listOf(known, unknownSize)) { _, _ -> }

        service.readEntryText(container, "known.txt") shouldBe "known size"
        service.readEntryText(container, "unknown.txt") shouldBe "size resolved at write time"
    }

    @Test
    fun `entry names the reader would reject or reinterpret are refused`() = runTest2 {
        val service = create()
        val container = LocalPath.build(File(workDir, "unsafe.zip"))

        suspend fun compressName(name: String) {
            val (entry, _) = sourceFile("payload_${name.hashCode()}.txt", "data")
            service.compress(ArchiveWriteOptions(ArchiveFormat.ZIP), container, listOf(entry.copy(name = name))) { _, _ -> }
        }

        shouldThrow<WriteException> { compressName("evil\\backslash.txt") }
        shouldThrow<WriteException> { compressName("../escape.txt") }
        shouldThrow<WriteException> { compressName("nul\u0000byte.txt") }
        shouldThrow<WriteException> { compressName((1..65).joinToString("/") { "d$it" }) }
        container.file.exists() shouldBe false
    }

    @Test
    fun `zip aborts when written bytes do not match the declared size`() = runTest2 {
        val service = create()
        val file = File(workDir, "grew.txt").apply { writeText("actual bytes") }
        // Declared size lies about the source (as if it changed between enumeration and write).
        val entry = ArchiveService.WriteEntry("grew.txt", LocalPath.build(file), isDirectory = false, size = 999_999L)
        val container = LocalPath.build(File(workDir, "mismatch.zip"))

        shouldThrow<WriteException> {
            service.compress(ArchiveWriteOptions(ArchiveFormat.ZIP), container, listOf(entry)) { _, _ -> }
        }
    }

    @Test
    fun `entry count above reader limit is refused`() = runTest2 {
        val service = create()
        val container = LocalPath.build(File(workDir, "many.zip"))
        val entries = (0 until 50_001).map { dirEntry("dir$it") }

        shouldThrow<WriteException> {
            service.compress(ArchiveWriteOptions(ArchiveFormat.ZIP), container, entries) { _, _ -> }
        }
        container.file.exists() shouldBe false
    }

    @Test
    fun `invalidate drops a stale cached index`() = runTest2 {
        val service = create()
        val (fileA, _) = sourceFile("a.txt", "version one")
        val container = LocalPath.build(File(workDir, "stale.zip"))

        service.compress(ArchiveWriteOptions(ArchiveFormat.ZIP), container, listOf(fileA)) { _, _ -> }

        // Pin the container stat so replacing the archive keeps an identical fingerprint,
        // mimicking a backend with coarse/null mtimes.
        pinnedContainerStat = container.file to container.file.toLookup()
        service.index(container).entriesBySegments.keys shouldBe setOf(listOf("a.txt"))

        val (fileB, _) = sourceFile("b.txt", "version two")
        service.compress(ArchiveWriteOptions(ArchiveFormat.ZIP), container, listOf(fileB)) { _, _ -> }

        // Unchanged fingerprint: the stale index survives...
        service.index(container).entriesBySegments.keys shouldBe setOf(listOf("a.txt"))
        // ...until invalidate() drops it.
        service.invalidate(container)
        service.index(container).entriesBySegments.keys shouldBe setOf(listOf("b.txt"))
    }

    @Test
    fun `write options enforce password invariants and redact toString`() {
        shouldThrow<IllegalArgumentException> {
            ArchiveWriteOptions(ArchiveFormat.ZIP, password = charArrayOf())
        }
        shouldThrow<IllegalArgumentException> {
            ArchiveWriteOptions(ArchiveFormat.TAR_GZ, password = "secret".toCharArray())
        }
        val options = ArchiveWriteOptions(ArchiveFormat.ZIP, password = "hunter2".toCharArray())
        options.toString() shouldNotContain "hunter2"
        options.toString() shouldNotBe null
    }
}
