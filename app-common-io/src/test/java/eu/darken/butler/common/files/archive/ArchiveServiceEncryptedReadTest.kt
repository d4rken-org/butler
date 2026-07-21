package eu.darken.butler.common.files.archive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
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
import okio.Path.Companion.toOkioPath
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
class ArchiveServiceEncryptedReadTest : BaseTest() {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val dispatcherProvider = TestDispatcherProvider()
    private val passwordStore = ArchivePasswordStore()
    private val cacheDir = File(context.cacheDir, "archives")

    private lateinit var workDir: File
    private lateinit var gatewaySwitch: GatewaySwitch

    @Before
    fun setup() {
        workDir = File(context.cacheDir, "encrypted_read_test").apply {
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
            coEvery { file(any(), any()) } answers {
                okio.FileSystem.SYSTEM.openReadOnly(firstArg<LocalPath>().file.toOkioPath())
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

    private fun buildZip(
        name: String,
        entries: Map<String, String>,
        password: String? = null,
        encryption: EncryptionMethod? = null,
        method: CompressionMethod = CompressionMethod.DEFLATE,
        plainEntries: Map<String, String> = emptyMap(),
    ): LocalPath {
        val zipFile = File(workDir, name)
        val zip = if (password != null) ZipFile(zipFile, password.toCharArray()) else ZipFile(zipFile)
        zip.use {
            entries.forEach { (entryName, content) ->
                val src = File(workDir, "src_${entryName.replace('/', '_')}").apply { writeText(content) }
                it.addFile(
                    src,
                    ZipParameters().apply {
                        fileNameInZip = entryName
                        compressionMethod = method
                        if (encryption != null) {
                            isEncryptFiles = true
                            encryptionMethod = encryption
                            if (encryption == EncryptionMethod.AES) aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                        }
                    },
                )
            }
            plainEntries.forEach { (entryName, content) ->
                val src = File(workDir, "src_${entryName.replace('/', '_')}").apply { writeText(content) }
                it.addFile(
                    src,
                    ZipParameters().apply {
                        fileNameInZip = entryName
                        compressionMethod = method
                    },
                )
            }
        }
        return LocalPath.build(zipFile)
    }

    private suspend fun ArchiveService.readEntryText(container: LocalPath, vararg segments: String): String =
        openEntryStream(ArchivePath(container, segments.toList())).use { it.readBytes().decodeToString() }

    private fun assertNoContainerCache() {
        (cacheDir.listFiles() ?: emptyArray()).none { it.name.startsWith("container-") } shouldBe true
    }

    @Test
    fun `aes and zipcrypto entries decrypt with store and deflate`() = runTest2 {
        val service = create()
        val variants = listOf(
            "aes_deflate.zip" to (EncryptionMethod.AES to CompressionMethod.DEFLATE),
            "aes_store.zip" to (EncryptionMethod.AES to CompressionMethod.STORE),
            "zc_deflate.zip" to (EncryptionMethod.ZIP_STANDARD to CompressionMethod.DEFLATE),
            "zc_store.zip" to (EncryptionMethod.ZIP_STANDARD to CompressionMethod.STORE),
        )
        variants.forEach { (name, config) ->
            val (encryption, method) = config
            val container = buildZip(
                name = name,
                entries = mapOf("secret.txt" to "payload for $name"),
                password = "hunter2",
                encryption = encryption,
                method = method,
            )
            passwordStore.set(container, "hunter2".toCharArray())
            service.readEntryText(container, "secret.txt") shouldBe "payload for $name"
        }
        assertNoContainerCache()
    }

    @Test
    fun `useEntryStreams delivers mixed plain and encrypted entries`() = runTest2 {
        val service = create()
        val container = buildZip(
            name = "mixed.zip",
            entries = mapOf("enc/a.txt" to "encrypted alpha", "enc/b.txt" to "encrypted beta"),
            password = "hunter2",
            encryption = EncryptionMethod.AES,
            plainEntries = mapOf("plain.txt" to "plain content"),
        )
        passwordStore.set(container, "hunter2".toCharArray())

        val index = service.index(container)
        val wanted = index.entriesBySegments.values.filter { !it.isDirectory }
        val contents = mutableMapOf<String, String>()
        service.useEntryStreams(container, wanted) { meta, input ->
            contents[meta.rawName] = input.readBytes().decodeToString()
        }

        contents.keys shouldContainExactlyInAnyOrder listOf("enc/a.txt", "enc/b.txt", "plain.txt")
        contents["enc/a.txt"] shouldBe "encrypted alpha"
        contents["enc/b.txt"] shouldBe "encrypted beta"
        contents["plain.txt"] shouldBe "plain content"
        assertNoContainerCache()
    }

    @Test
    fun `materializeEntry caches decrypted content under the entrydec prefix`() = runTest2 {
        val service = create()
        val container = buildZip(
            name = "materialize.zip",
            entries = mapOf("doc.txt" to "decrypted plaintext"),
            password = "hunter2",
            encryption = EncryptionMethod.AES,
        )
        passwordStore.set(container, "hunter2".toCharArray())

        val materialized = service.materializeEntry(ArchivePath(container, listOf("doc.txt")))

        materialized.readText() shouldBe "decrypted plaintext"
        materialized.parentFile shouldBe cacheDir
        materialized.name shouldStartWith "entrydec-"
        assertNoContainerCache()
    }

    @Test
    fun `verifyPassword accepts the right password and rejects a wrong one`() = runTest2 {
        val service = create()
        val container = buildZip(
            name = "verify.zip",
            entries = mapOf("a.txt" to "content a", "b.txt" to "content b"),
            password = "hunter2",
            encryption = EncryptionMethod.AES,
        )

        service.verifyPassword(container, "hunter2".toCharArray()) shouldBe true
        service.verifyPassword(container, "wrong".toCharArray()) shouldBe false
        assertNoContainerCache()
    }

    @Test
    fun `verifyPassword is true for an archive without encrypted entries`() = runTest2 {
        val service = create()
        val container = buildZip(name = "plain.zip", entries = mapOf("a.txt" to "content"))

        service.verifyPassword(container, "anything".toCharArray()) shouldBe true
        assertNoContainerCache()
    }

    @Test
    fun `wrong cached password fails the read and is evicted`() = runTest2 {
        val service = create()
        val container = buildZip(
            name = "wrongpw.zip",
            entries = mapOf("secret.txt" to "payload"),
            password = "hunter2",
            encryption = EncryptionMethod.AES,
        )
        passwordStore.set(container, "totally-wrong".toCharArray())

        val e = shouldThrow<ArchivePasswordRequiredException> {
            service.readEntryText(container, "secret.txt")
        }

        e.attemptFailed shouldBe true
        passwordStore.get(container) shouldBe null
        assertNoContainerCache()
    }

    @Test
    fun `missing password surfaces as password required without attempt`() = runTest2 {
        val service = create()
        val container = buildZip(
            name = "nopw.zip",
            entries = mapOf("secret.txt" to "payload"),
            password = "hunter2",
            encryption = EncryptionMethod.AES,
        )

        val e = shouldThrow<ArchivePasswordRequiredException> {
            service.readEntryText(container, "secret.txt")
        }

        e.attemptFailed shouldBe false
        assertNoContainerCache()
    }
}
