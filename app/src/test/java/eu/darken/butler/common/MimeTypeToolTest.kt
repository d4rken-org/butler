package eu.darken.butler.common

import android.webkit.MimeTypeMap
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MimeTypeToolTest : BaseTest() {

    private val tool = MimeTypeTool()
    private val defaultLocale = Locale.getDefault()

    /** Above API 30 the shadow proxies the real map unless the mappings were cleared first. */
    @Before
    fun isolatePlatformMap() {
        Shadows.shadowOf(MimeTypeMap.getSingleton()).clearMappings()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    private fun lookup(name: String): APathLookup<*> = LocalPathLookup(
        lookedUp = LocalPath.build("/storage/emulated/0/Download/$name"),
        fileType = FileType.FILE,
        size = 128L,
        modifiedAt = null,
    )

    private fun registerPlatformMapping(extension: String, mimeType: String) {
        Shadows.shadowOf(MimeTypeMap.getSingleton()).addExtensionMimeTypeMapping(extension, mimeType)
    }

    @Test
    fun `butler's own table answers for a text extension`() = runTest {
        tool.determineMimeType(lookup("notes.yaml")) shouldBe "text/plain"
    }

    @Test
    fun `an extension only the platform knows resolves through the fallback`() = runTest {
        registerPlatformMapping("tiff", "image/tiff")

        tool.determineMimeType(lookup("scan.tiff")) shouldBe "image/tiff"
    }

    @Test
    fun `an extension nobody knows stays unknown`() = runTest {
        tool.determineMimeType(lookup("blob.xyz")) shouldBe MimeTypes.Unknown.value
    }

    @Test
    fun `butler's table wins over a conflicting platform mapping`() = runTest {
        registerPlatformMapping("yaml", "application/x-yaml")

        tool.determineMimeType(lookup("notes.yaml")) shouldBe "text/plain"
    }

    @Test
    fun `an uppercase platform-only extension resolves under a turkish locale`() = runTest {
        registerPlatformMapping("tiff", "image/tiff")
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))

        tool.determineMimeType(lookup("SCAN.TIFF")) shouldBe "image/tiff"
    }
}
