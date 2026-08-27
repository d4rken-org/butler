package eu.darken.butler.workspace.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.TestApplication

// Robolectric: the type fallback is a string resource, so asserting what it renders needs a context.
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class WorkspaceTabLabelTest : BaseTest() {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun info(customTitle: String?) = Workspace.Info(
        id = Workspace.Id(),
        type = Workspace.Type.EXPLORER,
        title = "/storage/emulated/0/Download".toCaString(),
        customTitle = customTitle,
    )

    @Test
    fun `a custom name wins`() {
        info("Holiday photos").tabLabel.get(context) shouldBe "Holiday photos"
    }

    @Test
    fun `without a custom name the type names the tab`() {
        info(null).tabLabel.get(context) shouldBe "Explorer"
    }

    @Test
    fun `a blank custom name falls back to the type`() {
        info("   ").tabLabel.get(context) shouldBe "Explorer"
    }
}
