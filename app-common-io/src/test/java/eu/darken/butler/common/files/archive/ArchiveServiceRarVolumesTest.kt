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
import eu.darken.butler.common.files.errors.PathPermissionDeniedException
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
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
import java.util.Collections
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Multi-part sets from lib-rar's fixtures, see `lib-rar/src/test/resources/fixtures/README.md`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ArchiveServiceRarVolumesTest : BaseTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val dispatcherProvider = TestDispatcherProvider()
    private val passwordStore = ArchivePasswordStore()
    private val cacheDir = File(context.cacheDir, "archives")

    private lateinit var workDir: File
    private lateinit var gatewaySwitch: GatewaySwitch

    /** File names in the order the gateway opened them. */
    private val opened = Collections.synchronizedList(mutableListOf<String>())

    /** Runs right before the gateway opens a file. */
    private var beforeOpen: (File) -> Unit = {}

    @Before
    fun setup() {
        workDir = File(context.cacheDir, "rar_volumes_test").apply {
            deleteRecursively()
            mkdirs()
        }
        cacheDir.deleteRecursively()
        gatewaySwitch = mockk<GatewaySwitch>().apply {
            coEvery { openInputStream(any()) } answers {
                val file = firstArg<LocalPath>().file
                opened += file.name
                beforeOpen(file)
                file.inputStream()
            }
            coEvery { existsStrict(any()) } answers {
                if (firstArg<LocalPath>().file.exists()) Existence.PRESENT else Existence.ABSENT
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

    private fun resource(path: String): ByteArray = javaClass.classLoader!!.getResourceAsStream(path)!!.use { it.readBytes() }

    private val manifest by lazy { Json.parseToJsonElement(resource("rar723/manifest.json").decodeToString()).jsonObject }

    private fun fixtureOf(name: String) = manifest["fixtures"]!!.jsonObject[name]!!.jsonObject

    /** Copies every volume of the set that starts with [first] into the work directory. */
    private fun volumeSet(first: String): LocalPath {
        fixtureOf(first)["files"]!!.jsonArray.map { it.jsonPrimitive.content }.forEach { name ->
            File(workDir, name).writeBytes(resource("rar723/$name"))
        }
        return LocalPath.build(File(workDir, first))
    }

    private fun volume(name: String) = File(workDir, name)

    private class ManifestFile(val ordinal: Int, val name: String, val size: Long, val sha256: String)

    /** One per file: the manifest lists a split entry once per volume it occupies. */
    private fun manifestFiles(fixture: String): List<ManifestFile> =
        fixtureOf(fixture)["entries"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it["type"]!!.jsonPrimitive.content == "file" && !it["continuation"]!!.jsonPrimitive.boolean }
            .map {
                ManifestFile(
                    ordinal = it["ordinal"]!!.jsonPrimitive.int,
                    name = it["name"]!!.jsonPrimitive.content,
                    size = it["size"]!!.jsonPrimitive.long,
                    sha256 = it["sha256"]!!.jsonPrimitive.content,
                )
            }
            .sortedBy { it.ordinal }

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

    private fun opensOf(container: LocalPath): Int = opened.count { it == container.name }

    @Test
    fun `multi-part sets list and extract every file`() = runTest2 {
        mapOf(
            "vol-plain.part1.rar" to null,
            "vol-solid.part1.rar" to null,
            "vol-hp.part1.rar" to "password",
            "vol-legacy.rar" to null,
        ).forEach { (first, password) ->
            val service = create()
            val container = volumeSet(first)
            password?.let { passwordStore.set(container, it.toCharArray()) }
            val expected = manifestFiles(first)

            val index = service.index(container)

            index.volumes shouldBe fixtureOf(first)["files"]!!.jsonArray.map { LocalPath.build(volume(it.jsonPrimitive.content)) }
            index.entriesBySegments.values.filter { !it.isDirectory }.associate { it.rawName to it.size } shouldBe
                expected.associate { it.name to it.size }
            service.readAllHashes(container) shouldBe expected.map { it.name to it.sha256 }
        }
    }

    @Test
    fun `a split entry reads to the end after openEntryStream returned`() = runTest2 {
        val service = create()
        val plain = volumeSet("vol-plain.part1.rar")
        val encrypted = volumeSet("vol-hp.part1.rar")
        passwordStore.set(encrypted, "password".toCharArray())

        // random.bin spans the first two parts of vol-plain and all three of vol-hp.
        listOf(plain to "vol-plain.part1.rar", encrypted to "vol-hp.part1.rar").forEach { (container, fixture) ->
            val stream = service.openEntryStream(ArchivePath(container, listOf("random.bin")))
            stream.use { it.sha256() } shouldBe manifestFiles(fixture).single { it.name == "random.bin" }.sha256
        }
    }

    @Test
    fun `a missing later part is named`() = runTest2 {
        listOf(
            "vol-plain.part1.rar" to "vol-plain.part2.rar",
            "vol-plain.part1.rar" to "vol-plain.part3.rar",
            "vol-legacy.rar" to "vol-legacy.r00",
            "vol-legacy.rar" to "vol-legacy.r01",
        ).forEach { (first, missing) ->
            val container = volumeSet(first)
            volume(missing).delete()

            shouldThrow<ArchiveMissingPartException> { create().index(container) }.partName shouldBe missing
        }
    }

    @Test
    fun `a missing part is named when a split entry reaches it`() = runTest2 {
        val service = create()
        val container = volumeSet("vol-plain.part1.rar")
        val index = service.index(container)
        volume("vol-plain.part3.rar").delete()
        val lorem = index.entriesBySegments[listOf("lorem.txt")]!!

        // The handler directly, so the index is not rebuilt first.
        val handler = RarArchiveHandler(ArchiveHandlerContext({ gatewaySwitch }, passwordStore, volumeFingerprint = { "test" }))
        shouldThrow<ArchiveMissingPartException> {
            handler.openEntry(container, index, lorem).use { it.sha256() }
        }.partName shouldBe "vol-plain.part3.rar"
    }

    @Test
    fun `a later part that exists but cannot be opened surfaces the gateway's error`() = runTest2 {
        val container = volumeSet("vol-plain.part1.rar")
        val part2 = LocalPath.build(volume("vol-plain.part2.rar"))
        coEvery { gatewaySwitch.openInputStream(part2) } throws PathPermissionDeniedException(
            path = part2,
            operation = "openInputStream",
            reason = PathPermissionDeniedException.Reason.NOT_PERMITTED,
        )

        val e = shouldThrow<PathPermissionDeniedException> { create().index(container) }

        e.path shouldBe part2
    }

    @Test
    fun `a later part whose existence is unknown is opened`() = runTest2 {
        val container = volumeSet("vol-plain.part1.rar")
        coEvery { gatewaySwitch.existsStrict(any()) } returns Existence.UNKNOWN

        create().readAllHashes(container) shouldBe manifestFiles("vol-plain.part1.rar").map { it.name to it.sha256 }
    }

    @Test
    fun `a later part opened directly points to the first part`() = runTest2 {
        volumeSet("vol-plain.part1.rar")
        val container = LocalPath.build(volume("vol-plain.part2.rar"))

        shouldThrow<ArchiveUnsupportedException> { create().index(container) }.reason shouldBe
            ArchiveUnsupportedException.Reason.NotFirstPart(2)
    }

    @Test
    fun `an unchanged set is served from cache`() = runTest2 {
        val service = create()
        val container = volumeSet("vol-plain.part1.rar")

        val first = service.index(container)
        val second = service.index(container)

        second shouldBe first
        opened shouldBe listOf("vol-plain.part1.rar", "vol-plain.part2.rar", "vol-plain.part3.rar")
    }

    @Test
    fun `changing a later part rebuilds the index and changes entry cache keys`() = runTest2 {
        val service = create()
        val container = volumeSet("vol-plain.part1.rar")
        val small = ArchivePath(container, listOf("small.txt"))
        val expected = manifestFiles("vol-plain.part1.rar").single { it.name == "small.txt" }.sha256

        val before = service.index(container)
        val cached = service.materializeEntry(small)
        cached.inputStream().use { it.sha256() } shouldBe expected
        // Stands in for content of the old set: a key that did not change would serve it.
        cached.writeText("stale")

        val part3 = volume("vol-plain.part3.rar")
        part3.setLastModified(part3.lastModified() + 10_000)

        var opens = opensOf(container)
        val after = service.index(container)
        after.fingerprint shouldNotBe before.fingerprint
        opensOf(container) shouldBe opens + 1
        service.materializeEntry(small).inputStream().use { it.sha256() } shouldBe expected

        // A size change; the appended bytes follow the end of the archive.
        part3.writeBytes(part3.readBytes() + ByteArray(16))
        opens = opensOf(container)
        service.index(container).fingerprint shouldNotBe after.fingerprint
        opensOf(container) shouldBe opens + 1
    }

    @Test
    fun `deleting a later part makes the cached index unusable`() = runTest2 {
        val service = create()
        val container = volumeSet("vol-plain.part1.rar")
        service.index(container)

        volume("vol-plain.part2.rar").delete()

        shouldThrow<ArchiveMissingPartException> { service.index(container) }.partName shouldBe "vol-plain.part2.rar"
        shouldThrow<ArchiveMissingPartException> {
            service.openEntryStream(ArchivePath(container, listOf("small.txt")))
        }
    }

    @Test
    fun `a later part changed during the scan forces a rebuild`() = runTest2 {
        val service = create()
        val container = volumeSet("vol-plain.part1.rar")
        val part2 = volume("vol-plain.part2.rar")
        var changed = false
        beforeOpen = { file ->
            if (file == part2 && !changed) {
                changed = true
                part2.setLastModified(part2.lastModified() + 10_000)
            }
        }

        val index = service.index(container)

        opensOf(container) shouldBe 2
        index.fingerprint shouldBe service.index(container).fingerprint
        opensOf(container) shouldBe 2
    }

    @Test
    fun `cancelling a scan cancels a pending open of the next part`() = runBlocking {
        val container = volumeSet("vol-plain.part1.rar")
        val part2 = LocalPath.build(volume("vol-plain.part2.rar"))
        val pending = CompletableDeferred<Unit>()
        coEvery { gatewaySwitch.existsStrict(part2) } coAnswers {
            pending.complete(Unit)
            awaitCancellation()
        }
        val service = create()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val outcome = CompletableDeferred<Throwable?>()
        val scan = scope.launch {
            try {
                service.index(container)
                outcome.complete(null)
            } catch (e: Throwable) {
                outcome.complete(e)
                throw e
            }
        }
        withTimeout(10.seconds) { pending.await() }
        scan.cancel()

        withTimeout(10.seconds) { outcome.await() }.shouldBeInstanceOf<CancellationException>()
        scope.cancel()
    }
}
