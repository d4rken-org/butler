package eu.darken.butler.main.core.external

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.storage.DocumentUriResolver
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import java.io.File

/**
 * Lives in :app because the router is keyed to the application's own FileProvider authority.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalOpenRouterTest : BaseTest() {

    private lateinit var application: Application
    private val documentUriResolver = mockk<DocumentUriResolver>()
    private val importer = mockk<ExternalContentImporter>()

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()
    }

    private fun create() = ExternalOpenRouter(
        context = application,
        documentUriResolver = documentUriResolver,
        importer = importer,
        privatePathPrefixes = ExternalOpenRouter.PRIVATE_PATH_PREFIXES,
    )

    private fun canonical(path: String) = File(path).canonicalPath

    private fun ownUri(path: String) = Uri.parse("content://${application.packageName}.provider$path")

    // ==================== sanitize ====================

    @Test
    fun `only file and content URIs are accepted`() {
        val router = create()

        router.sanitize(Uri.parse("http://example.com/x.png")).shouldBeNull()
        router.sanitize(Uri.parse("butler://open/x.png")).shouldBeNull()
    }

    @Test
    fun `a file URI on shared storage becomes a local path`() {
        val ref = create().sanitize(Uri.parse("file:///sdcard/Download/x.png"))

        ref.shouldBeInstanceOf<SourceRef.Local>().path shouldBe
            LocalPath.build(File(canonical("/sdcard/Download/x.png")))
    }

    @Test
    fun `a file URI into app-private storage is refused`() {
        create().sanitize(Uri.parse("file:///data/data/eu.darken.butler/shared_prefs/x.xml")).shouldBeNull()
    }

    @Test
    fun `path traversal into app-private storage is refused`() {
        create().sanitize(Uri.parse("file:///sdcard/../data/data/eu.darken.butler/secret")).shouldBeNull()
    }

    @Test
    fun `our own provider URI is mapped back to the file it stands for`() {
        val ref = create().sanitize(ownUri("/device_root/sdcard/x.png"))

        ref.shouldBeInstanceOf<SourceRef.Local>().path shouldBe
            LocalPath.build(File(canonical("/sdcard/x.png")))
    }

    @Test
    fun `our own provider URI into app-private storage is refused`() {
        create().sanitize(ownUri("/device_root/data/data/eu.darken.butler/databases/x.db")).shouldBeNull()
    }

    @Test
    fun `our own provider URI outside the device root is refused`() {
        create().sanitize(ownUri("/bug_reports/report.zip")).shouldBeNull()
    }

    @Test
    fun `a foreign provider URI stays content`() {
        val uri = Uri.parse("content://com.example.files/document/42")

        create().sanitize(uri).shouldBeInstanceOf<SourceRef.Content>().uri shouldBe uri
    }

    // ==================== resolveMime ====================

    @Test
    fun `the type declared by the caller wins`() {
        create().resolveMime("image/png", "application/pdf", "x.txt") shouldBe MimeInfo("image/png")
    }

    @Test
    fun `types are normalized`() {
        create().resolveMime("Text/Plain; charset=utf-8", null, "x.bin") shouldBe MimeInfo("text/plain")
    }

    @Test
    fun `generic types fall through to the provider`() {
        val router = create()

        router.resolveMime("*/*", "image/png", "x.bin") shouldBe MimeInfo("image/png")
        router.resolveMime("application/octet-stream", "image/png", "x.bin") shouldBe MimeInfo("image/png")
        router.resolveMime("   ", "image/png", "x.bin") shouldBe MimeInfo("image/png")
    }

    @Test
    fun `the file name decides when nothing else says anything`() {
        create().resolveMime("*/*", "application/octet-stream", "holiday.jpg") shouldBe MimeInfo("image/jpeg")
    }

    @Test
    fun `an unknown file name ends up as generic binary`() {
        create().resolveMime(null, null, "mystery") shouldBe MimeInfo("application/octet-stream")
    }

    // ==================== resolveForView ====================

    @Test
    fun `a local image whose name matches is opened directly`() = runTest {
        val ref = SourceRef.Local(LocalPath.build(File("/sdcard/holiday.jpg")))

        create().resolveForView(ref, MimeInfo("image/jpeg"), "holiday.jpg") shouldBe ref.path
    }

    @Test
    fun `a local image without an image name is imported so it gets an extension`() = runTest {
        val ref = SourceRef.Local(LocalPath.build(File("/sdcard/holiday")))
        val imported = LocalPath.build(File("/cache/external_open/uuid/holiday.jpg"))
        coEvery { importer.importToCache(any(), any(), any()) } returns imported

        create().resolveForView(ref, MimeInfo("image/jpeg"), "holiday") shouldBe imported
    }

    @Test
    fun `a content URI the document resolver knows is opened directly`() = runTest {
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3Ax.png")
        val resolved = LocalPath.build(File("/sdcard/x.png"))
        every { documentUriResolver.resolve(uri) } returns resolved

        create().resolveForView(SourceRef.Content(uri), MimeInfo("image/png"), "x.png") shouldBe resolved
    }

    @Test
    fun `a content URI the document resolver knows still honors the extension rule`() = runTest {
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3Ax")
        val imported = LocalPath.build(File("/cache/external_open/uuid/x.png"))
        every { documentUriResolver.resolve(uri) } returns LocalPath.build(File("/sdcard/x"))
        coEvery { importer.importToCache(any(), any(), any()) } returns imported

        create().resolveForView(SourceRef.Content(uri), MimeInfo("image/png"), "x") shouldBe imported
    }

    @Test
    fun `an unresolvable content URI is imported into the cache`() = runTest {
        val uri = Uri.parse("content://com.example.files/document/42")
        val imported = LocalPath.build(File("/cache/external_open/uuid/x.png"))
        every { documentUriResolver.resolve(uri) } returns null
        coEvery { importer.importToCache(uri, "x.png", MimeInfo("image/png")) } returns imported

        create().resolveForView(SourceRef.Content(uri), MimeInfo("image/png"), "x.png") shouldBe imported
    }
}
