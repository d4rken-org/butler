package eu.darken.butler.main.core.external

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.cache.CacheRepo
import eu.darken.butler.common.files.MimeInfo
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalContentImporterTest : BaseTest() {

    private lateinit var application: Application
    private val cacheRepo = mockk<CacheRepo>()

    private val uri: Uri = Uri.parse("content://com.example.files/document/42")

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()
        coEvery { cacheRepo.canSpare(any()) } returns true
    }

    private fun create() = ExternalContentImporter(
        context = application,
        dispatcherProvider = TestDispatcherProvider(),
        cacheRepo = cacheRepo,
    )

    private val importDir: File
        get() = File(application.cacheDir, "external_open")

    private fun register(uri: Uri, stream: () -> InputStream) {
        shadowOf(application.contentResolver).registerInputStreamSupplier(uri, stream)
    }

    private fun failingStream(bytesBeforeFailure: Int, error: () -> Throwable) = object : InputStream() {
        private var served = 0

        override fun read(): Int {
            if (served >= bytesBeforeFailure) throw error()
            served++
            return 'a'.code
        }
    }

    @Test
    fun `content is copied into the cache under its display name`() = runTest {
        register(uri) { ByteArrayInputStream("Hello World".toByteArray()) }

        val imported = create().importToCache(uri, "notes.txt", MimeInfo("text/plain")).shouldNotBeNull()

        imported.file.name shouldBe "notes.txt"
        imported.file.readText() shouldBe "Hello World"
        imported.file.parentFile!!.parentFile shouldBe importDir
    }

    @Test
    fun `path separators and null chars are stripped from the display name`() = runTest {
        register(uri) { ByteArrayInputStream("x".toByteArray()) }

        val imported = create().importToCache(uri, "../etc/pass\\wd\u0000 .txt", null).shouldNotBeNull()

        imported.file.name shouldBe "..etcpasswd .txt"
    }

    @Test
    fun `a blank display name falls back`() = runTest {
        register(uri) { ByteArrayInputStream("x".toByteArray()) }

        create().importToCache(uri, "   ", null).shouldNotBeNull().file.name shouldBe "file"
    }

    @Test
    fun `an extensionless image gets an extension from its type`() = runTest {
        register(uri) { ByteArrayInputStream("x".toByteArray()) }

        val imported = create().importToCache(uri, "holiday", MimeInfo("image/png")).shouldNotBeNull()

        imported.file.name shouldBe "holiday.png"
    }

    @Test
    fun `an extensionless pdf gets an extension from its type`() = runTest {
        register(uri) { ByteArrayInputStream("x".toByteArray()) }

        val imported = create().importToCache(uri, "invoice", MimeInfo("application/pdf")).shouldNotBeNull()

        imported.file.name shouldBe "invoice.pdf"
    }

    @Test
    fun `an image that already has a matching name is left alone`() = runTest {
        register(uri) { ByteArrayInputStream("x".toByteArray()) }

        val imported = create().importToCache(uri, "holiday.jpg", MimeInfo("image/jpeg")).shouldNotBeNull()

        imported.file.name shouldBe "holiday.jpg"
    }

    @Test
    fun `a failure mid-stream leaves nothing behind`() = runTest {
        register(uri) { failingStream(8) { IOException("Connection lost") } }

        create().importToCache(uri, "notes.txt", null).shouldBeNull()

        importDir.listFiles().orEmpty().toList() shouldBe emptyList()
    }

    @Test
    fun `cancellation is rethrown and leaves nothing behind`() = runTest {
        register(uri) { failingStream(8) { CancellationException("Nevermind") } }

        shouldThrow<CancellationException> {
            create().importToCache(uri, "notes.txt", null)
        }

        importDir.listFiles().orEmpty().toList() shouldBe emptyList()
    }

    @Test
    fun `an import is refused when the cache has no room`() = runTest {
        val source = File(application.cacheDir, "source.txt").apply { writeText("Hello World") }
        coEvery { cacheRepo.canSpare(any()) } returns false

        create().importToCache(Uri.fromFile(source), "notes.txt", null).shouldBeNull()

        importDir.listFiles().orEmpty().toList() shouldBe emptyList()
    }

    @Test
    fun `an import is refused when the cache has no room and the size is unknown`() = runTest {
        register(uri) { ByteArrayInputStream("Hello World".toByteArray()) }
        coEvery { cacheRepo.canSpare(any()) } returns false

        create().importToCache(uri, "notes.txt", null).shouldBeNull()

        importDir.listFiles().orEmpty().toList() shouldBe emptyList()
    }

    @Test
    fun `stale imports are swept but fresh ones survive`() = runTest {
        val stale = File(importDir, "stale").apply { mkdirs() }
        val staleFile = File(stale, "old.txt").apply { writeText("old") }
        val fresh = File(importDir, "fresh").apply { mkdirs() }
        val eightDaysAgo = System.currentTimeMillis() - 8 * 24 * 60 * 60 * 1000L
        stale.setLastModified(eightDaysAgo)
        register(uri) { ByteArrayInputStream("x".toByteArray()) }

        create().importToCache(uri, "notes.txt", null).shouldNotBeNull()

        stale.exists() shouldBe false
        staleFile.exists() shouldBe false
        fresh.exists() shouldBe true
    }
}
