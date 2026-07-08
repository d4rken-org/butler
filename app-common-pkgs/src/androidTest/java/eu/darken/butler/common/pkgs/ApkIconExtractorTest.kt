package eu.darken.butler.common.pkgs

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.darken.butler.common.coroutine.DefaultDispatcherProvider
import eu.darken.butler.common.files.preview.PreviewBudget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device verification of the no-copy APK icon route (API 30+). Uses installed foreign apps as APK
 * sources (a file-backed [ParcelFileDescriptor] is a faithful stand-in for a seekable share fd).
 */
@RunWith(AndroidJUnit4::class)
class ApkIconExtractorTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val extractor = ApkIconExtractor(context, DefaultDispatcherProvider())

    @Test
    fun extractsForeignAppIconWithoutCopying() = runBlocking {
        assumeTrue("Route (a) needs API 30+", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)

        val candidates = context.packageManager.getInstalledPackages(0)
            .mapNotNull { it.applicationInfo }
            .filter { it.packageName != context.packageName && it.icon != 0 }
            .filter { it.sourceDir != null && File(it.sourceDir).canRead() }
            .take(8)
        assumeTrue("Need at least one readable icon-bearing app", candidates.isNotEmpty())

        var successes = 0
        for (appInfo in candidates) {
            val pfd = ParcelFileDescriptor.open(File(appInfo.sourceDir), ParcelFileDescriptor.MODE_READ_ONLY)
            val bmp = pfd.use { extractor.extract(it, PreviewBudget.MAX_ICON_DIM) }
            if (bmp != null) {
                successes++
                assertTrue("width ${bmp.width} out of bounds", bmp.width in 1..PreviewBudget.MAX_ICON_DIM)
                assertTrue("height ${bmp.height} out of bounds", bmp.height in 1..PreviewBudget.MAX_ICON_DIM)
            }
        }
        assertTrue("expected >=1 extracted icon, got $successes/${candidates.size}", successes > 0)
    }

    @Test
    fun nonApkReturnsNull() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)

        val tmp = File.createTempFile("notapk", ".bin", context.cacheDir).apply {
            writeText("definitely not an apk")
        }
        try {
            val pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
            val bmp = pfd.use { extractor.extract(it, PreviewBudget.MAX_ICON_DIM) }
            assertNull(bmp)
        } finally {
            tmp.delete()
        }
    }
}
