package eu.darken.butler.common.files.archive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.error.localized
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.formatFileSize
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Fixtures are lib-rar's, see `lib-rar/src/test/resources/fixtures/README.md`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ArchiveServiceRarTest : BaseTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val dispatcherProvider = TestDispatcherProvider()
    private val passwordStore = ArchivePasswordStore()
    private val cacheDir = File(context.cacheDir, "archives")

    private lateinit var workDir: File
    private lateinit var gatewaySwitch: GatewaySwitch

    @Before
    fun setup() {
        workDir = File(context.cacheDir, "rar_test").apply {
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

    private fun handler(windowBudgetBytes: Long) = RarArchiveHandler(
        ArchiveHandlerContext({ gatewaySwitch }, passwordStore),
        windowBudgetBytes,
    )

    private fun resource(path: String): ByteArray = javaClass.classLoader!!.getResourceAsStream(path)!!.use { it.readBytes() }

    /** Copies `rar723/[name]` (or another fixture path) into the work directory, without its sibling volumes. */
    private fun fixture(name: String, path: String = "rar723/$name", edit: (ByteArray) -> ByteArray = { it }): LocalPath {
        val target = File(workDir, name)
        target.writeBytes(edit(resource(path)))
        return LocalPath.build(target)
    }

    private class ManifestEntry(val ordinal: Int, val name: String, val type: String, val size: Long, val sha256: String?)

    private val manifest by lazy { Json.parseToJsonElement(resource("rar723/manifest.json").decodeToString()).jsonObject }

    private fun manifestEntries(fixture: String): List<ManifestEntry> =
        manifest["fixtures"]!!.jsonObject[fixture]!!.jsonObject["entries"]!!.jsonArray.map { element ->
            val entry = element.jsonObject
            ManifestEntry(
                ordinal = entry["ordinal"]!!.jsonPrimitive.int,
                name = entry["name"]!!.jsonPrimitive.content,
                type = entry["type"]!!.jsonPrimitive.content,
                size = entry["size"]!!.jsonPrimitive.long,
                sha256 = entry["sha256"]?.jsonPrimitive?.takeIf { it.isString }?.content,
            )
        }

    private fun manifestFiles(fixture: String) = manifestEntries(fixture).filter { it.type == "file" }

    private fun InputStream.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val read = read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun ArchiveService.readAllHashes(container: LocalPath): List<Pair<String, String>> {
        val files = index(container).entriesBySegments.values.filter { !it.isDirectory && !it.isSymlink }
        val delivered = mutableListOf<Pair<String, String>>()
        useEntryStreams(container, files) { meta, input -> delivered += meta.rawName to input.sha256() }
        return delivered
    }

    private fun Throwable.description(): String = localized(context).description.get(context)

    private fun LocalPath.readable(): String = userReadablePath.get(context)

    private suspend fun RarArchiveHandler.indexOf(container: LocalPath): ArchiveIndex {
        val scan = scan(container, ArchiveFormat.RAR)
        val (bySegments, children) = buildIndexMaps(scan.entries)
        return ArchiveIndex(
            container = container,
            format = ArchiveFormat.RAR,
            fingerprint = "test",
            entriesBySegments = bySegments,
            childrenBySegments = children,
            skippedUnsafe = scan.skippedUnsafe,
            skippedSpecial = scan.skippedSpecial,
            isHeaderEncrypted = scan.headerEncrypted,
            passwordGeneration = scan.passwordGeneration,
        )
    }

    @Test
    fun `stored and solid archives list every entry, Unicode names included`() = runTest2 {
        val service = create()
        listOf("stored.rar", "solid.rar").forEach { name ->
            val container = fixture(name)
            val index = service.index(container)

            index.format shouldBe ArchiveFormat.RAR
            index.isEncrypted shouldBe false
            index.skippedSpecial shouldBe 0
            index.skippedUnsafe shouldBe 0
            val entries = manifestEntries(name)
            val files = index.entriesBySegments.values.filter { !it.isDirectory }
            files.associate { it.rawName to it.size } shouldBe manifestFiles(name).associate { it.name to it.size }
            files.associate { it.rawName to it.ordinal } shouldBe manifestFiles(name).associate { it.name to it.ordinal }
            entries.filter { it.type == "directory" }.forEach { dir ->
                index.entriesBySegments[dir.name.split('/')]!!.let {
                    it.isDirectory shouldBe true
                    it.synthesized shouldBe false
                }
            }
            val nested = index.entriesBySegments[listOf("unicode", "русский", "файл.txt")]!!
            nested.modifiedAt shouldNotBe null
            // Only stored.rar was created with -ts+, which keeps nanoseconds.
            if (name == "stored.rar") nested.modifiedAt shouldBe Instant.fromEpochSeconds(1_700_000_000, 123_456_789)
            index.entriesBySegments[listOf("unicode", "emoji 😀🎉.txt")]!!.size shouldBe 30L
        }
    }

    @Test
    fun `a compressed solid entry reads to the end after openEntryStream returned`() = runTest2 {
        val service = create()
        val container = fixture("solid.rar")
        val expected = manifestFiles("solid.rar").single { it.name == "docs/numbers.txt" }

        val stream = service.openEntryStream(ArchivePath(container, listOf("docs", "numbers.txt")))

        stream.use { it.sha256() } shouldBe expected.sha256
    }

    @Test
    fun `stored entries read through openEntryStream`() = runTest2 {
        val service = create()
        val container = fixture("stored.rar")
        manifestFiles("stored.rar").forEach { file ->
            service.openEntryStream(ArchivePath(container, file.name.split('/'))).use { it.sha256() } shouldBe file.sha256
        }
    }

    @Test
    fun `useEntryStreams delivers every file in archive order`() = runTest2 {
        val service = create()
        listOf("stored.rar", "solid.rar").forEach { name ->
            val container = fixture(name)
            service.readAllHashes(container) shouldBe manifestFiles(name).sortedBy { it.ordinal }.map { it.name to it.sha256 }
        }
    }

    @Test
    fun `encrypted headers need the password to list`() = runTest2 {
        val service = create()
        val container = fixture("encrypted-headers.rar")

        shouldThrow<ArchivePasswordRequiredException> { service.index(container) }.attemptFailed shouldBe false

        passwordStore.set(container, "nope".toCharArray())
        shouldThrow<ArchivePasswordRequiredException> { service.index(container) }.apply {
            attemptFailed shouldBe true
            mayBeDamaged shouldBe false
        }
        passwordStore.get(container) shouldBe null

        passwordStore.set(container, "password".toCharArray())
        val index = service.index(container)
        index.isHeaderEncrypted shouldBe true
        index.passwordGeneration shouldBe passwordStore.generation(container)
        index.entriesBySegments.values.filter { !it.isDirectory }.map { it.rawName }.toSet() shouldBe
            manifestFiles("encrypted-headers.rar").map { it.name }.toSet()
        service.requiresPassword(container) shouldBe false
        service.readAllHashes(container) shouldBe
            manifestFiles("encrypted-headers.rar").sortedBy { it.ordinal }.map { it.name to it.sha256 }
    }

    @Test
    fun `encrypted data lists without a password and reads with it`() = runTest2 {
        listOf("encrypted-data.rar", "solid-encrypted.rar").forEach { name ->
            val service = create()
            val container = fixture(name)
            val files = manifestFiles(name)
            val numbers = files.single { it.name == "docs/numbers.txt" }

            val index = service.index(container)
            index.isHeaderEncrypted shouldBe false
            index.entriesBySegments.values.filter { !it.isDirectory }.all { it.isEncrypted } shouldBe true
            shouldThrow<ArchivePasswordRequiredException> {
                service.openEntryStream(ArchivePath(container, listOf("docs", "numbers.txt"))).use { it.sha256() }
            }.attemptFailed shouldBe false

            passwordStore.set(container, "password".toCharArray())
            service.openEntryStream(ArchivePath(container, listOf("docs", "numbers.txt"))).use { it.sha256() } shouldBe
                numbers.sha256
            service.readAllHashes(container) shouldBe files.sortedBy { it.ordinal }.map { it.name to it.sha256 }
        }
    }

    @Test
    fun `requiresPassword checks the cached password and evicts a wrong one`() = runTest2 {
        val service = create()
        val container = fixture("encrypted-data.rar")

        service.requiresPassword(container) shouldBe true

        passwordStore.set(container, "password".toCharArray())
        service.requiresPassword(container) shouldBe false
        passwordStore.get(container) shouldNotBe null

        passwordStore.set(container, "nope".toCharArray())
        service.requiresPassword(container) shouldBe true
        passwordStore.get(container) shouldBe null
    }

    @Test
    fun `a wrong cached password evicts on read`() = runTest2 {
        val service = create()
        val container = fixture("encrypted-data.rar")
        passwordStore.set(container, "nope".toCharArray())

        shouldThrow<ArchivePasswordRequiredException> {
            service.openEntryStream(ArchivePath(container, listOf("hello.txt"))).use { it.sha256() }
        }.apply {
            attemptFailed shouldBe true
            mayBeDamaged shouldBe false
        }
        passwordStore.get(container) shouldBe null
    }

    @Test
    fun `an uncertain wrong password in requiresPassword prompts with the damage caveat and is evicted`() = runTest2 {
        val service = create()
        val container = fixture("encrypted-data.rar") { RarHeaderEdit.clearCheckValue(it, "hello.txt") }
        passwordStore.set(container, "nope".toCharArray())

        shouldThrow<ArchivePasswordRequiredException> { service.requiresPassword(container) }.apply {
            attemptFailed shouldBe true
            mayBeDamaged shouldBe true
        }
        passwordStore.get(container) shouldBe null

        passwordStore.set(container, "password".toCharArray())
        service.requiresPassword(container) shouldBe false
    }

    @Test
    fun `verifyPassword does not evict`() = runTest2 {
        val service = create()
        val container = fixture("encrypted-data.rar")
        passwordStore.set(container, "password".toCharArray())

        service.verifyPassword(container, "nope".toCharArray()) shouldBe false
        service.verifyPassword(container, "password".toCharArray()) shouldBe true
        passwordStore.get(container) shouldNotBe null

        val headers = fixture("encrypted-headers.rar")
        service.verifyPassword(headers, "nope".toCharArray()) shouldBe false
        service.verifyPassword(headers, "password".toCharArray()) shouldBe true
    }

    @Test
    fun `symbolic links are indexed as links`() = runTest2 {
        val service = create()
        val container = fixture("symlinks.rar")

        val index = service.index(container)
        index.skippedSpecial shouldBe 0
        listOf("sub/up-target", "to-target", "to-sub").forEach { name ->
            index.entriesBySegments[name.split('/')]!!.apply {
                isSymlink shouldBe true
                isDirectory shouldBe false
                size shouldBe null
                linkTarget shouldNotBe null
            }
        }
        index.entriesBySegments[listOf("target.txt")]!!.isSymlink shouldBe false
        shouldThrow<ReadException> { service.openEntryStream(ArchivePath(container, listOf("to-target"))) }
        service.openEntryStream(ArchivePath(container, listOf("sub", "inner.txt"))).use { it.sha256() } shouldBe
            manifestFiles("symlinks.rar").single { it.name == "sub/inner.txt" }.sha256
    }

    @Test
    fun `hard links, file copies and unknown redirections are skipped as special`() = runTest2 {
        val service = create()
        mapOf("hardlinks.rar" to "second.txt", "filecopy.rar" to "copy.bin").forEach { (name, skipped) ->
            val index = service.index(fixture(name))
            index.skippedSpecial shouldBe 1
            index.entriesBySegments[listOf(skipped)] shouldBe null
        }

        val unknown = fixture("unknown-redirection.rar", path = "rar723/symlinks.rar") {
            RarHeaderEdit.setRedirectionType(it, "to-target", 0x7F)
        }
        val index = service.index(unknown)
        index.skippedSpecial shouldBe 1
        index.entriesBySegments[listOf("to-target")] shouldBe null
        index.entriesBySegments[listOf("to-sub")]!!.isSymlink shouldBe true
    }

    @Test
    fun `a RAR4 archive is refused with an explanation`() = runTest2 {
        val container = fixture("old.rar", path = "libarchive/test_read_format_rar.rar")

        val e = shouldThrow<ArchiveUnsupportedException> { create().index(container) }

        e.reason shouldBe ArchiveUnsupportedException.Reason.Rar4
        e.description() shouldBe
            "${container.readable()} uses the older RAR4 format, which can't be opened. Only RAR5 archives are supported."
    }

    @Test
    fun `an unsupported compression feature lists but fails to read`() = runTest2 {
        val service = create()
        val container = fixture("dict-5g-v1.rar")
        service.index(container).entriesBySegments[listOf("zero.bin")] shouldNotBe null

        val e = shouldThrow<ArchiveUnsupportedException> { service.openEntryStream(ArchivePath(container, listOf("zero.bin"))) }

        e.reason shouldBe ArchiveUnsupportedException.Reason.Feature
        e.description() shouldBe "${container.readable()} uses a RAR feature that isn't supported."
    }

    @Test
    fun `a truncated archive is reported as damaged`() = runTest2 {
        val container = fixture("stored.rar") { it.copyOf(it.size / 2) }

        val e = shouldThrow<ArchiveDamagedException> { create().index(container) }

        e.description() shouldBe "${container.readable()} is damaged or incomplete and can't be read."
    }

    @Test
    fun `a first part without its siblings names the missing part`() = runTest2 {
        val container = fixture("vol-plain.part1.rar")

        val e = shouldThrow<ArchiveMissingPartException> { create().index(container) }

        e.partName shouldBe "vol-plain.part2.rar"
        e.description() shouldBe "${container.readable()} is a multi-part archive, but its part vol-plain.part2.rar is missing."
    }

    @Test
    fun `a later part opened directly points to the first part`() = runTest2 {
        val container = fixture("vol-plain.part2.rar")

        val e = shouldThrow<ArchiveUnsupportedException> { create().index(container) }

        e.reason shouldBe ArchiveUnsupportedException.Reason.NotFirstPart(2)
        e.description() shouldBe
            "${container.readable()} is part 2 of a multi-part archive. Open the first part instead."
    }

    @Test
    fun `a dictionary larger than the memory budget is refused with both sizes`() = runTest2 {
        val handler = handler(windowBudgetBytes = 512L * 1024)
        val container = fixture("dict-1m.rar")
        val index = handler.indexOf(container)

        val e = shouldThrow<ArchiveUnsupportedException> {
            handler.openEntry(container, index, index.entriesBySegments[listOf("dictdata.bin")]!!)
        }

        e.reason shouldBe ArchiveUnsupportedException.Reason.DictionaryTooLarge(1024L * 1024, 512L * 1024)
        e.description() shouldBe "${container.readable()} can't be unpacked: it needs " +
            "${formatFileSize(context, 1024L * 1024)} of memory, but only ${formatFileSize(context, 512L * 1024)} is available."
    }

    @Test
    fun `a second reader waits for the budget and stops waiting when cancelled`() = runBlocking {
        val handler = handler(windowBudgetBytes = 1024L * 1024)
        val container = fixture("dict-1m.rar")
        val index = handler.indexOf(container)
        val meta = index.entriesBySegments[listOf("dictdata.bin")]!!
        val expected = manifestFiles("dict-1m.rar").single().sha256
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = scope.async {
            handler.useEntryStreams(container, index, listOf(meta)) { _, input ->
                input.read()
                holding.complete(Unit)
                release.await()
            }
        }
        withTimeout(10.seconds) { holding.await() }

        val cancelledOutcome = CompletableDeferred<Throwable?>()
        val cancelled = scope.launch {
            try {
                handler.useEntryStreams(container, index, listOf(meta)) { _, input -> input.sha256() }
                cancelledOutcome.complete(null)
            } catch (e: Throwable) {
                cancelledOutcome.complete(e)
                throw e
            }
        }
        awaitWaiters(handler, 1)
        cancelled.cancel()
        withTimeout(10.seconds) { cancelledOutcome.await() }.shouldBeInstanceOf<CancellationException>()
        awaitWaiters(handler, 0)

        val patient = scope.async {
            var hash: String? = null
            handler.useEntryStreams(container, index, listOf(meta)) { _, input -> hash = input.sha256() }
            hash
        }
        awaitWaiters(handler, 1)
        release.complete(Unit)
        withTimeout(10.seconds) {
            holder.await()
            patient.await() shouldBe expected
        }
        scope.cancel()
    }

    @Test
    fun `openEntry waiting for the budget ends with CancellationException when its coroutine is cancelled`() = runBlocking {
        val handler = handler(windowBudgetBytes = 1024L * 1024)
        val container = fixture("dict-1m.rar")
        val index = handler.indexOf(container)
        val meta = index.entriesBySegments[listOf("dictdata.bin")]!!
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val held = handler.openEntry(container, index, meta)

        val outcome = CompletableDeferred<Throwable?>()
        val waiter = scope.launch {
            try {
                withContext(Dispatchers.IO) { handler.openEntry(container, index, meta) }.close()
                outcome.complete(null)
            } catch (e: Throwable) {
                outcome.complete(e)
                throw e
            }
        }
        awaitWaiters(handler, 1)
        waiter.cancel()
        withTimeout(10.seconds) { outcome.await() }.shouldBeInstanceOf<CancellationException>()
        awaitWaiters(handler, 0)

        held.close()
        handler.openEntry(container, index, meta).use { it.sha256() } shouldBe manifestFiles("dict-1m.rar").single().sha256
        scope.cancel()
    }

    private suspend fun awaitWaiters(handler: RarArchiveHandler, count: Int) = withContext(Dispatchers.IO) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (handler.windowBudget.waiting != count) {
            check(System.nanoTime() < deadline) { "Expected $count budget waiters, have ${handler.windowBudget.waiting}" }
            Thread.sleep(10)
        }
    }

    /** Edits plain RAR5 file headers of a fixture and reseals their CRC. */
    private object RarHeaderEdit {
        private const val SIGNATURE_SIZE = 8
        private const val FILE_HEADER = 2L
        private const val END_HEADER = 5L
        private const val RECORD_CRYPT = 0x01L
        private const val RECORD_REDIRECTION = 0x05L

        /** Drops the password check value flag of [name]'s encryption record; its bytes stay as ignored record data. */
        fun clearCheckValue(bytes: ByteArray, name: String): ByteArray = editRecord(bytes, name, RECORD_CRYPT) { data ->
            // Version, then flags: 0x01 check value present, 0x02 checksums are MACs.
            check(bytes[data + 1].toInt() == 0x03)
            bytes[data + 1] = 0x02
        }

        fun setRedirectionType(bytes: ByteArray, name: String, type: Int): ByteArray =
            editRecord(bytes, name, RECORD_REDIRECTION) { data -> bytes[data] = type.toByte() }

        private fun editRecord(bytes: ByteArray, name: String, recordType: Long, edit: (dataStart: Int) -> Unit): ByteArray {
            var pos = SIGNATURE_SIZE
            while (pos < bytes.size) {
                val (headerSize, headerStart) = vint(bytes, pos + 4)
                val headerEnd = headerStart + headerSize.toInt()
                var (type, p) = vint(bytes, headerStart)
                val flags = vint(bytes, p).also { p = it.second }.first
                val extraSize = if (flags and 0x01L != 0L) vint(bytes, p).also { p = it.second }.first else 0L
                val dataSize = if (flags and 0x02L != 0L) vint(bytes, p).also { p = it.second }.first else 0L
                if (type == FILE_HEADER && fileName(bytes, p) == name) {
                    var record = headerEnd - extraSize.toInt()
                    while (record < headerEnd) {
                        val (size, sizeEnd) = vint(bytes, record)
                        val (id, dataStart) = vint(bytes, sizeEnd)
                        if (id == recordType) {
                            edit(dataStart)
                            val crc = CRC32().apply { update(bytes, pos + 4, headerEnd - pos - 4) }.value
                            for (i in 0 until 4) bytes[pos + i] = (crc ushr (8 * i)).toByte()
                            return bytes
                        }
                        record = sizeEnd + size.toInt()
                    }
                }
                if (type == END_HEADER) break
                pos = headerEnd + dataSize.toInt()
            }
            error("No record $recordType for $name")
        }

        /** File flags, unpacked size, attributes, optional mtime and CRC, compression, host OS, then the name. */
        private fun fileName(bytes: ByteArray, fieldsStart: Int): String {
            val (fileFlags, afterFlags) = vint(bytes, fieldsStart)
            var p = vint(bytes, vint(bytes, afterFlags).second).second
            if (fileFlags and 0x02L != 0L) p += 4
            if (fileFlags and 0x04L != 0L) p += 4
            p = vint(bytes, vint(bytes, p).second).second
            val (length, nameStart) = vint(bytes, p)
            return String(bytes, nameStart, length.toInt(), Charsets.UTF_8)
        }

        private fun vint(bytes: ByteArray, start: Int): Pair<Long, Int> {
            var value = 0L
            var shift = 0
            var p = start
            while (true) {
                val b = bytes[p++].toInt() and 0xFF
                value = value or ((b and 0x7F).toLong() shl shift)
                shift += 7
                if (b and 0x80 == 0) return value to p
            }
        }
    }
}
