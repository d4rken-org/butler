package eu.darken.butler.common.files.archive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldNotBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okio.FileHandle
import okio.Path.Companion.toOkioPath
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
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

/** Fixtures and how they were made: `src/test/resources/archive/sevenz/README.md`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ArchiveServiceSevenZTest : BaseTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val dispatcherProvider = TestDispatcherProvider()
    private val passwordStore = ArchivePasswordStore()
    private val cacheDir = File(context.cacheDir, "archives")

    private lateinit var workDir: File
    private lateinit var gatewaySwitch: GatewaySwitch

    /** Flipped by a test to make every positioned read except the seekability probe fail. */
    @Volatile private var failStorageReads = false

    /** Flipped by a test to make every size query after the seekability probe's first one fail. */
    @Volatile private var failStorageSize = false

    @Before
    fun setup() {
        workDir = File(context.cacheDir, "sevenz_test").apply {
            deleteRecursively()
            mkdirs()
        }
        cacheDir.deleteRecursively()
        gatewaySwitch = mockk<GatewaySwitch>().apply {
            coEvery { openInputStream(any()) } answers { firstArg<LocalPath>().file.inputStream() }
            coEvery { lookup(any(), any<LookupOptions>()) } answers {
                @Suppress("UNCHECKED_CAST")
                firstArg<LocalPath>().file.toLookup() as APathLookup<APath<*>>
            }
            coEvery { file(any(), any()) } answers {
                val real = okio.FileSystem.SYSTEM.openReadOnly(firstArg<LocalPath>().file.toOkioPath())
                FlakyHandle(real, failingReads = { failStorageReads }, failingSize = { failStorageSize })
            }
        }
    }

    @After
    fun teardown() {
        appScope.cancel()
        workDir.deleteRecursively()
        cacheDir.deleteRecursively()
    }

    private class FlakyHandle(
        private val real: FileHandle,
        private val failingReads: () -> Boolean,
        private val failingSize: () -> Boolean,
    ) : FileHandle(readWrite = false) {
        private var sizeQueries = 0

        override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int {
            if (failingReads() && fileOffset != real.size() - 1) throw IOException("storage went away")
            return real.read(fileOffset, array, arrayOffset, byteCount)
        }

        override fun protectedSize(): Long {
            if (failingSize() && ++sizeQueries > 1) throw IOException("storage went away")
            return real.size()
        }
        override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) =
            throw IOException("read-only")

        override fun protectedResize(size: Long) = throw IOException("read-only")
        override fun protectedFlush() = Unit
        override fun protectedClose() = real.close()
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

    private fun fixture(name: String): LocalPath {
        val target = File(workDir, name)
        javaClass.classLoader!!.getResourceAsStream("archive/sevenz/$name")!!.use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return LocalPath.build(target)
    }

    private suspend fun ArchiveService.readText(container: LocalPath, vararg segments: String): String =
        openEntryStream(ArchivePath(container, segments.toList())).use { it.readBytes().decodeToString() }

    private suspend fun ArchiveService.readAllFiles(container: LocalPath): Map<String, String> {
        val files = index(container).entriesBySegments.values.filter { !it.isDirectory && !it.isSymlink }
        val contents = mutableMapOf<String, String>()
        useEntryStreams(container, files) { meta, input -> contents[meta.rawName] = input.readBytes().decodeToString() }
        return contents
    }

    private val numbers = (1..2000).joinToString("\n", postfix = "\n")

    private val expectedFiles = mapOf(
        "a.txt" to "alpha\n",
        "docs/b.txt" to "bravo bravo bravo\n",
        "docs/nested/c.txt" to "charlie\n",
        "docs/numbers.txt" to numbers,
    )

    @Test
    fun `solid archive indexes and reads single entries and batches`() = runTest2 {
        val service = create()
        val container = fixture("solid.7z")

        val index = service.index(container)
        index.format shouldBe ArchiveFormat.SEVEN_Z
        index.isEncrypted shouldBe false
        index.entriesBySegments.keys.map { it.joinToString("/") } shouldContainExactlyInAnyOrder listOf(
            "a.txt", "docs", "docs/b.txt", "docs/nested", "docs/nested/c.txt", "docs/numbers.txt",
        )
        index.entriesBySegments[listOf("docs", "numbers.txt")]!!.size shouldBe numbers.length.toLong()
        index.entriesBySegments[listOf("a.txt")]!!.modifiedAt shouldNotBe null

        service.readText(container, "docs", "numbers.txt") shouldBe numbers
        service.readAllFiles(container) shouldBe expectedFiles
    }

    @Test
    fun `content encryption lists without a password and reads with it`() = runTest2 {
        val service = create()
        val container = fixture("content-encrypted.7z")

        val index = service.index(container)
        index.isHeaderEncrypted shouldBe false
        index.entriesBySegments.values.filter { !it.isDirectory }.all { it.isEncrypted } shouldBe true
        service.requiresPassword(container) shouldBe true

        shouldThrow<ArchivePasswordRequiredException> { service.readText(container, "a.txt") }
            .attemptFailed shouldBe false

        passwordStore.set(container, "secret".toCharArray())
        service.requiresPassword(container) shouldBe false
        service.readText(container, "a.txt") shouldBe "alpha\n"
        service.readAllFiles(container) shouldBe expectedFiles
    }

    @Test
    fun `wrong cached password on encrypted content re-prompts and is evicted`() = runTest2 {
        val service = create()
        val container = fixture("content-encrypted.7z")
        passwordStore.set(container, "nope".toCharArray())

        shouldThrow<ArchivePasswordRequiredException> { service.readText(container, "docs", "numbers.txt") }
            .attemptFailed shouldBe true
        passwordStore.get(container) shouldBe null
    }

    @Test
    fun `encrypted entry list needs the password to index`() = runTest2 {
        val service = create()
        val container = fixture("header-encrypted.7z")

        shouldThrow<ArchivePasswordRequiredException> { service.index(container) }.attemptFailed shouldBe false

        passwordStore.set(container, "nope".toCharArray())
        shouldThrow<ArchivePasswordRequiredException> { service.index(container) }.attemptFailed shouldBe true
        passwordStore.get(container) shouldBe null

        passwordStore.set(container, "secret".toCharArray())
        val index = service.index(container)
        index.isHeaderEncrypted shouldBe true
        index.isEncrypted shouldBe true
        service.requiresPassword(container) shouldBe false
        service.readAllFiles(container) shouldBe expectedFiles
    }

    @Test
    fun `verifyPassword works without an index for an encrypted entry list`() = runTest2 {
        val service = create()
        val container = fixture("header-encrypted.7z")

        service.verifyPassword(container, "nope".toCharArray()) shouldBe false
        service.verifyPassword(container, "secret".toCharArray()) shouldBe true
    }

    @Test
    fun `verifyPassword catches a wrong key on uncompressed content through the CRC`() = runTest2 {
        // Copy-coded content decrypts to garbage without any decoder error; only the CRC tells.
        val service = create()
        val container = fixture("copy-encrypted.7z")

        service.verifyPassword(container, "nope".toCharArray()) shouldBe false
        service.verifyPassword(container, "secret".toCharArray()) shouldBe true
    }

    @Test
    fun `unsupported codec stays a codec error even with a password`() = runTest2 {
        val service = create()
        val container = fixture("ppmd-encrypted.7z")
        passwordStore.set(container, "secret".toCharArray())

        val e = shouldThrow<ReadException> { service.readText(container, "a.txt") }

        e.shouldNotBeInstanceOf<ArchivePasswordRequiredException>()
        e.message shouldContain "Unsupported 7z compression method"
        passwordStore.get(container) shouldNotBe null
    }

    @Test
    fun `storage failure during an encrypted read is not a password problem`() = runTest2 {
        val service = create()
        val container = fixture("content-encrypted.7z")
        passwordStore.set(container, "secret".toCharArray())
        service.index(container)

        failStorageReads = true
        val e = shouldThrow<IOException> { service.readText(container, "a.txt") }

        e.shouldNotBeInstanceOf<ArchivePasswordRequiredException>()
        e.message shouldBe "storage went away"
        passwordStore.get(container) shouldNotBe null
    }

    @Test
    fun `symlinks are indexed as links and not read`() = runTest2 {
        val service = create()
        val container = fixture("symlink.7z")

        val index = service.index(container)
        index.entriesBySegments[listOf("link-to-a")]!!.isSymlink shouldBe true
        index.entriesBySegments[listOf("a.txt")]!!.isSymlink shouldBe false

        shouldThrow<ReadException> { service.readText(container, "link-to-a") }
        service.readText(container, "a.txt") shouldBe "alpha\n"
    }

    @Test
    fun `duplicate names resolve to the last entry everywhere`() = runTest2 {
        val service = create()
        val file = File(workDir, "dupes.7z")
        SevenZOutputFile(file).use { out ->
            listOf("dup.txt" to "first", "other.txt" to "other", "dup.txt" to "second").forEach { (name, text) ->
                out.putArchiveEntry(SevenZArchiveEntry().apply { this.name = name })
                out.write(text.toByteArray())
                out.closeArchiveEntry()
            }
        }
        val container = LocalPath.build(file)

        service.index(container).entriesBySegments[listOf("dup.txt")]!!.ordinal shouldBe 2
        service.readText(container, "dup.txt") shouldBe "second"
        service.readAllFiles(container) shouldBe mapOf("dup.txt" to "second", "other.txt" to "other")
    }

    @Test
    fun `a failing size query with a password in play is not a password problem`() = runTest2 {
        val service = create()
        val container = fixture("content-encrypted.7z")
        passwordStore.set(container, "secret".toCharArray())
        service.index(container)

        failStorageSize = true
        val e = shouldThrow<IOException> { service.readText(container, "a.txt") }

        e.shouldNotBeInstanceOf<ArchivePasswordRequiredException>()
        passwordStore.get(container) shouldNotBe null
    }

    @Test
    fun `wrong-password failures say the archive may be damaged`() = runTest2 {
        val service = create()
        val container = fixture("header-encrypted.7z")
        passwordStore.set(container, "nope".toCharArray())

        shouldThrow<ArchivePasswordRequiredException> { service.index(container) }.mayBeDamaged shouldBe true
    }

    @Test
    fun `a cached encrypted listing is not served once the password is gone`() = runTest2 {
        val service = create()
        val container = fixture("header-encrypted.7z")
        passwordStore.set(container, "secret".toCharArray())
        service.index(container)

        passwordStore.evict(container)

        shouldThrow<ArchivePasswordRequiredException> { service.index(container) }
    }

    @Test
    fun `cached plaintext is not served once the password is gone`() = runTest2 {
        val service = create()
        val container = fixture("content-encrypted.7z")
        val entry = ArchivePath(container, listOf("a.txt"))
        passwordStore.set(container, "secret".toCharArray())
        service.materializeEntry(entry).readText() shouldBe "alpha\n"

        passwordStore.evict(container)

        shouldThrow<ArchivePasswordRequiredException> { service.materializeEntry(entry) }
    }

    @Test
    fun `a cached password that fails verification is evicted`() = runTest2 {
        val service = create()
        val container = fixture("content-encrypted.7z")
        passwordStore.set(container, "nope".toCharArray())

        service.requiresPassword(container) shouldBe true
        passwordStore.get(container) shouldBe null
    }

    @Test
    fun `directories, empty files and anti-items keep ordinals aligned between listing and reading`() = runTest2 {
        val service = create()
        val file = File(workDir, "mixed.7z")
        SevenZOutputFile(file).use { out ->
            fun put(name: String, text: String? = null, configure: SevenZArchiveEntry.() -> Unit = {}) {
                out.putArchiveEntry(SevenZArchiveEntry().apply { this.name = name; configure() })
                text?.let { out.write(it.toByteArray()) }
                out.closeArchiveEntry()
            }
            put("dir") { isDirectory = true }
            put("dir/empty.txt")
            put("gone.txt") { isAntiItem = true }
            put("dir/one.txt", "one")
            put("empty-too.txt")
            put("two.txt", "two")
        }
        val container = LocalPath.build(file)

        val index = service.index(container)
        index.entriesBySegments[listOf("gone.txt")] shouldBe null
        index.skippedSpecial shouldBe 1
        service.readText(container, "dir", "one.txt") shouldBe "one"
        service.readText(container, "two.txt") shouldBe "two"
        service.readText(container, "dir", "empty.txt") shouldBe ""

        val subset = listOf(listOf("two.txt"), listOf("empty-too.txt")).map { index.entriesBySegments[it]!! }
        val contents = mutableMapOf<String, String>()
        service.useEntryStreams(container, subset) { meta, input -> contents[meta.rawName] = input.readBytes().decodeToString() }
        contents shouldBe mapOf("empty-too.txt" to "", "two.txt" to "two")
        service.readAllFiles(container) shouldBe mapOf(
            "dir/empty.txt" to "",
            "dir/one.txt" to "one",
            "empty-too.txt" to "",
            "two.txt" to "two",
        )
    }

    @Test
    fun `encrypted blocks after a plain first block take the cached password`() = runTest2 {
        val service = create()
        val container = fixture("mixed.7z")

        service.readText(container, "a.txt") shouldBe "alpha\n"
        shouldThrow<ArchivePasswordRequiredException> { service.readText(container, "docs", "b.txt") }
            .attemptFailed shouldBe false

        passwordStore.set(container, "secret".toCharArray())
        service.readText(container, "docs", "b.txt") shouldBe "bravo bravo bravo\n"
        service.readAllFiles(container) shouldBe expectedFiles
    }

    @Test
    fun `plaintext of a mixed archive is not served from cache once the password is gone`() = runTest2 {
        val service = create()
        val container = fixture("mixed.7z")
        val entry = ArchivePath(container, listOf("docs", "b.txt"))
        passwordStore.set(container, "secret".toCharArray())
        service.materializeEntry(entry).readText() shouldBe "bravo bravo bravo\n"

        passwordStore.evict(container)

        shouldThrow<ArchivePasswordRequiredException> { service.materializeEntry(entry) }
    }

    @Test
    fun `a wrong password does not unlock a listing cached for the right one`() = runTest2 {
        val service = create()
        val container = fixture("header-encrypted.7z")
        passwordStore.set(container, "secret".toCharArray())
        service.index(container)

        passwordStore.set(container, "nope".toCharArray())

        shouldThrow<ArchivePasswordRequiredException> { service.index(container) }.attemptFailed shouldBe true
    }

    @Test
    fun `damage behind a proven password is reported as damage, not as a wrong password`() = runTest2 {
        val service = create()
        val container = fixture("header-encrypted.7z")
        val bytes = container.file.readBytes()
        // Past the 32-byte signature header: packed entry data, the encrypted entry list sits at the end.
        bytes[40] = (bytes[40].toInt() xor 0xFF).toByte()
        container.file.writeBytes(bytes)
        passwordStore.set(container, "secret".toCharArray())

        service.verifyPassword(container, "secret".toCharArray()) shouldBe true
        val e = shouldThrow<ReadException> { service.readText(container, "a.txt") }

        e.shouldNotBeInstanceOf<ArchivePasswordRequiredException>()
        e.message shouldContain "Damaged"
        passwordStore.get(container) shouldNotBe null
    }

    @Test
    fun `7z archives cannot be written`() {
        shouldThrow<IllegalArgumentException> { ArchiveWriteOptions(format = ArchiveFormat.SEVEN_Z) }
    }
}
