package eu.darken.butler.apps.core.details

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.workspace.contracts.apps.AppDetailsArguments
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDetailsWorkspaceDisplayTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `the tab is named after the package until the label resolves`() {
        val display = deriveAppDetailsDisplay(AppDetailsArguments(packageName = "eu.darken.butler"))

        display.title!!.get(context) shouldBe "eu.darken.butler"
        display.subtitle shouldBe null
    }

    @Test
    fun `a blank package name is no name at all`() {
        listOf("", "   ").forEach { blank ->
            deriveAppDetailsDisplay(AppDetailsArguments(packageName = blank)).title shouldBe null
        }
    }
}
