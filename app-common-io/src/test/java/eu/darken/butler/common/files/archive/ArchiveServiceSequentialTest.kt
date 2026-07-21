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
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldNotBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.coroutine.runTest2
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ArchiveServiceSequentialTest : BaseTest() {

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
        workDir = File(context.cacheDir, "sequential_test").apply {
            deleteRecursively()
            mkdirs()
        }
        gatewaySwitch = mockk<GatewaySwitch>().apply {
            coEvery { openInputStream(any()) } answers {
                (firstArg<LocalPath>()).file.inputStream()
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

    /** Builds a zip via commons-compress, which allows arbitrary (even unsafe) entry names. */
    private fun buildZip(name: String, entries: List<Pair<String, String?>>): LocalPath {
        val file = File(workDir, name)
        ZipArchiveOutputStream(file).use { out ->
            entries.forEach { (entryName, content) ->
                out.putArchiveEntry(ZipArchiveEntry(entryName))
                content?.let { out.write(it.toByteArray()) }
                out.closeArchiveEntry()
            }
        }
        return LocalPath.build(file)
    }

    private fun buildEncryptedZip(
        name: String,
        entries: Map<String, String>,
        password: String,
        encryption: EncryptionMethod,
    ): LocalPath {
        val zipFile = File(workDir, name)
        ZipFile(zipFile, password.toCharArray()).use { zip ->
            entries.forEach { (entryName, content) ->
                val src = File(workDir, "src_${entryName.replace('/', '_')}").apply { writeText(content) }
                zip.addFile(
                    src,
                    ZipParameters().apply {
                        fileNameInZip = entryName
                        isEncryptFiles = true
                        encryptionMethod = encryption
                        if (encryption == EncryptionMethod.AES) aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                    },
                )
            }
        }
        return LocalPath.build(zipFile)
    }

    private class Delivered(
        val ordinal: Int,
        val segments: List<String>,
        val rawName: String,
        val content: String,
    )

    private suspend fun ArchiveService.collectAll(
        container: LocalPath,
        processedOrdinals: Set<Int> = emptySet(),
        into: MutableList<Delivered>,
    ): SequentialResult = extractZipSequential(
        container = container,
        processedOrdinals = processedOrdinals,
    ) { entry, input ->
        into += Delivered(entry.ordinal, entry.segments, entry.rawName, input.readBytes().decodeToString())
        SequentialOutcome.EXTRACTED
    }

    @Test
    fun `plain zip streams all file entries in order with correct content`() = runTest2 {
        val service = create()
        val container = buildZip(
            "plain.zip",
            listOf(
                "a.txt" to "alpha",
                "dir/" to null,
                "dir/b.txt" to "beta",
            ),
        )

        val delivered = mutableListOf<Delivered>()
        val result = service.collectAll(container, into = delivered)

        delivered.map { it.ordinal } shouldContainExactly listOf(0, 2)
        delivered.map { it.segments } shouldContainExactly listOf(listOf("a.txt"), listOf("dir", "b.txt"))
        delivered.map { it.content } shouldContainExactly listOf("alpha", "beta")
        // Directory entries consume an ordinal but are never delivered.
        delivered.none { it.rawName == "dir/" } shouldBe true
        result shouldBe SequentialResult(extracted = 2, skippedUnsafe = 0)
    }

    @Test
    fun `encrypted entries decrypt with a cached password`() = runTest2 {
        val service = create()
        listOf(
            "aes.zip" to EncryptionMethod.AES,
            "zc.zip" to EncryptionMethod.ZIP_STANDARD,
        ).forEach { (name, encryption) ->
            val container = buildEncryptedZip(
                name = name,
                entries = mapOf("secret.txt" to "payload of $name"),
                password = "hunter2",
                encryption = encryption,
            )
            passwordStore.set(container, "hunter2".toCharArray())

            val delivered = mutableListOf<Delivered>()
            val result = service.collectAll(container, into = delivered)

            delivered.single().content shouldBe "payload of $name"
            result.extracted shouldBe 1
        }
    }

    @Test
    fun `missing password aborts and a restart pass skips processed ordinals`() = runTest2 {
        val service = create()
        val container = buildEncryptedZip(
            name = "restart.zip",
            entries = mapOf("a.txt" to "alpha", "b.txt" to "beta"),
            password = "hunter2",
            encryption = EncryptionMethod.AES,
        )

        val e = shouldThrow<ArchivePasswordRequiredException> {
            service.collectAll(container, into = mutableListOf())
        }
        e.attemptFailed shouldBe false

        passwordStore.set(container, "hunter2".toCharArray())
        val firstPass = mutableListOf<Delivered>()
        service.collectAll(container, into = firstPass)
        firstPass.size shouldBe 2

        // Restart pass: entries handled before are drained but not delivered again.
        val handled = setOf(firstPass.first().ordinal)
        val secondPass = mutableListOf<Delivered>()
        val result = service.collectAll(container, processedOrdinals = handled, into = secondPass)

        secondPass.map { it.ordinal } shouldContainExactly listOf(firstPass.last().ordinal)
        result.extracted shouldBe 1
    }

    @Test
    fun `wrong cached password is evicted and reported as failed attempt`() = runTest2 {
        val service = create()
        val container = buildEncryptedZip(
            name = "wrongpw.zip",
            entries = mapOf("a.txt" to "alpha"),
            password = "hunter2",
            encryption = EncryptionMethod.AES,
        )
        passwordStore.set(container, "totally-wrong".toCharArray())

        val e = shouldThrow<ArchivePasswordRequiredException> {
            service.collectAll(container, into = mutableListOf())
        }

        e.attemptFailed shouldBe true
        passwordStore.get(container) shouldBe null
    }

    @Test
    fun `duplicate raw names are both delivered with distinct ordinals`() = runTest2 {
        val service = create()
        val container = buildZip(
            "dup.zip",
            listOf(
                "dup.txt" to "first",
                "dup.txt" to "second",
            ),
        )

        val delivered = mutableListOf<Delivered>()
        val result = service.collectAll(container, into = delivered)

        delivered.map { it.ordinal } shouldContainExactly listOf(0, 1)
        delivered.map { it.rawName } shouldContainExactly listOf("dup.txt", "dup.txt")
        delivered.map { it.content } shouldContainExactly listOf("first", "second")
        result.extracted shouldBe 2
    }

    @Test
    fun `unsafe entry names are skipped and counted`() = runTest2 {
        val service = create()
        // Note: a leading "/" sanitizes to a relative name and is delivered; genuinely unsafe are
        // traversal segments and NUL bytes (see ArchiveEntrySafety).
        val container = buildZip(
            "unsafe.zip",
            listOf(
                "../evil.txt" to "escape",
                "bad\u0000name.txt" to "nul",
                "ok.txt" to "fine",
            ),
        )

        val delivered = mutableListOf<Delivered>()
        val result = service.collectAll(container, into = delivered)

        delivered.map { it.segments } shouldContainExactly listOf(listOf("ok.txt"))
        result shouldBe SequentialResult(extracted = 1, skippedUnsafe = 2)
    }

    @Test
    fun `fingerprint mismatch aborts before streaming`() = runTest2 {
        val service = create()
        val container = buildZip("swap.zip", listOf("a.txt" to "alpha"))

        val e = shouldThrow<SequentialAbortException> {
            service.extractZipSequential(
                container = container,
                expectedFingerprint = "bogus:fingerprint:0",
            ) { _, _ -> SequentialOutcome.EXTRACTED }
        }

        e.extracted shouldBe 0
        e.message shouldContain "changed between extraction passes"
    }

    @Test
    fun `skipped policy outcome is not counted as extracted`() = runTest2 {
        val service = create()
        val container = buildZip("policy.zip", listOf("a.txt" to "alpha", "b.txt" to "beta"))

        val result = service.extractZipSequential(container) { _, input ->
            input.readBytes()
            SequentialOutcome.SKIPPED_POLICY
        }

        result.extracted shouldBe 0
        result.skippedUnsafe shouldBe 0
    }

    @Test
    fun `a file that is not a zip fails with zero headers`() = runTest2 {
        val service = create()
        val file = File(workDir, "fake.zip").apply { writeText("this is just plain text, no zip here") }
        val container = LocalPath.build(file)

        val e = shouldThrow<ReadException> {
            service.extractZipSequential(container) { _, _ -> SequentialOutcome.EXTRACTED }
        }

        e.shouldNotBeInstanceOf<SequentialAbortException>()
        e.message shouldContain "Not a streamable zip"
    }

    @Test
    fun `progress reports non-decreasing bytes against the container size`() = runTest2 {
        val service = create()
        val container = buildZip(
            "progress.zip",
            listOf("a.txt" to "alpha".repeat(1000), "b.txt" to "beta".repeat(1000)),
        )
        val containerSize = container.file.length()

        val progress = mutableListOf<Pair<Long, Long?>>()
        service.extractZipSequential(
            container = container,
            onContainerProgress = { read, total -> progress += read to total },
        ) { _, input ->
            input.readBytes()
            SequentialOutcome.EXTRACTED
        }

        progress.isNotEmpty() shouldBe true
        progress.zipWithNext().all { (a, b) -> a.first <= b.first } shouldBe true
        progress.all { it.second == containerSize } shouldBe true
    }

    @Test
    fun `unknown container size reports null progress totals`() = runTest2 {
        val service = create()
        val container = buildZip("nosize.zip", listOf("a.txt" to "alpha"))
        pinnedContainerStat = container.file to LocalPathLookup(
            lookedUp = container,
            fileType = FileType.FILE,
            size = null,
            modifiedAt = null,
        )

        val progress = mutableListOf<Pair<Long, Long?>>()
        service.extractZipSequential(
            container = container,
            onContainerProgress = { read, total -> progress += read to total },
        ) { _, input ->
            input.readBytes()
            SequentialOutcome.EXTRACTED
        }

        progress.isNotEmpty() shouldBe true
        progress.all { it.second == null } shouldBe true
    }

    @Test
    fun `streamed store entry with data descriptor aborts`() = runTest2 {
        val service = create()
        // Minimal local file header: method STORE, general purpose bit 3 (data descriptor) set,
        // all sizes zero - zip4j cannot know where the entry data ends.
        val name = "trap.txt".toByteArray()
        val bytes = ByteArrayOutputStream().apply {
            fun le16(v: Int) {
                write(v and 0xFF)
                write((v shr 8) and 0xFF)
            }

            fun le32(v: Int) {
                le16(v and 0xFFFF)
                le16((v shr 16) and 0xFFFF)
            }
            le32(0x04034b50) // local file header signature
            le16(20) // version needed
            le16(0x0008) // flags: data descriptor
            le16(0) // method: STORE
            le16(0) // mod time
            le16(0x0021) // mod date
            le32(0) // crc
            le32(0) // compressed size
            le32(0) // uncompressed size
            le16(name.size)
            le16(0) // extra length
            write(name)
        }.toByteArray()
        val file = File(workDir, "dd.zip").apply { writeBytes(bytes) }
        val container = LocalPath.build(file)

        shouldThrow<SequentialAbortException> {
            service.extractZipSequential(container) { _, _ -> SequentialOutcome.EXTRACTED }
        }
    }
}
