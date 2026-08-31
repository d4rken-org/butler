package eu.darken.butler.common.debug.bugreport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BugReportStorageLayoutTest : BaseTest() {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val externalRoot get() = File(context.getExternalFilesDir(null), "bugreports")
    private val privateRoot get() = File(context.filesDir, "bugreports")

    @Test
    fun `external storage comes first and is the write root`() {
        val layout = BugReportStorageLayout(context)

        layout.roots shouldBe listOf(externalRoot, privateRoot)
        layout.writeRoot shouldBe externalRoot
    }

    @Test
    fun `findReportDir searches both roots`() {
        val layout = BugReportStorageLayout(context)
        File(externalRoot, "external_1").mkdirs()
        File(privateRoot, "legacy_1").mkdirs()

        layout.findReportDir("external_1") shouldBe File(externalRoot, "external_1")
        layout.findReportDir("legacy_1") shouldBe File(privateRoot, "legacy_1")
        layout.findReportDir("unknown") shouldBe null
    }

    @Test
    fun `findReportDir prefers the external copy, allReportDirs returns both`() {
        val layout = BugReportStorageLayout(context)
        File(externalRoot, "dupe_1").mkdirs()
        File(privateRoot, "dupe_1").mkdirs()

        layout.findReportDir("dupe_1") shouldBe File(externalRoot, "dupe_1")
        layout.allReportDirs("dupe_1") shouldBe listOf(File(externalRoot, "dupe_1"), File(privateRoot, "dupe_1"))
        layout.allReportDirs("unknown") shouldBe emptyList()
    }
}
