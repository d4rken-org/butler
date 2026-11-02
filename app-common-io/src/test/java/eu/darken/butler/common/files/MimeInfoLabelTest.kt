package eu.darken.butler.common.files

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.EmptyApp

@RunWith(AndroidJUnit4::class)
@Config(sdk = [29], application = EmptyApp::class)
class MimeInfoLabelTest : BaseTest() {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `xz and bzip2 files are labelled as archives`() {
        val archiveLabel = MimeInfo("application/zip").toCaString().get(app)

        MimeInfo("application/x-xz").toCaString().get(app) shouldBe archiveLabel
        MimeInfo("application/x-bzip2").toCaString().get(app) shouldBe archiveLabel
    }
}
