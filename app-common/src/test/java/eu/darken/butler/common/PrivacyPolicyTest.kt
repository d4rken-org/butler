package eu.darken.butler.common

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import testhelpers.BaseTest

/** The consent dialogs route their privacy policy action here, so this is where the URL is pinned. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrivacyPolicyTest : BaseTest() {

    private val application: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the policy page opens`() {
        openPrivacyPolicy(application) shouldBe true

        shadowOf(application).nextStartedActivity!!.data shouldBe Uri.parse(ButlerLinks.PRIVACY_POLICY)
    }
}
