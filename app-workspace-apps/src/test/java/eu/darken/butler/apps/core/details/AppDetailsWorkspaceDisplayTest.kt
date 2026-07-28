package eu.darken.butler.apps.core.details

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.features.InstallId
import eu.darken.butler.common.user.UserHandle2
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

    private fun arguments(
        packageName: String = "eu.darken.butler",
        appLabel: String? = null,
    ) = AppDetailsArguments(
        installId = InstallId(Pkg.Id(packageName), UserHandle2(0)),
        appLabel = appLabel,
    )

    @Test
    fun `the tab is named after the package until the label resolves`() {
        val display = deriveAppDetailsDisplay(arguments())

        display.title!!.get(context) shouldBe "eu.darken.butler"
        display.subtitle shouldBe null
    }

    @Test
    fun `a cached label names the tab with the package below it`() {
        val display = deriveAppDetailsDisplay(arguments(appLabel = "Butler"))

        display.title!!.get(context) shouldBe "Butler"
        display.subtitle!!.get(context) shouldBe "eu.darken.butler"
    }

    @Test
    fun `a label equal to the package is no label at all`() {
        val display = deriveAppDetailsDisplay(arguments(appLabel = "eu.darken.butler"))

        display.title!!.get(context) shouldBe "eu.darken.butler"
        display.subtitle shouldBe null
    }

    @Test
    fun `a blank label counts as no label`() {
        listOf("", "   ").forEach { blank ->
            val display = deriveAppDetailsDisplay(arguments(appLabel = blank))

            display.title!!.get(context) shouldBe "eu.darken.butler"
            display.subtitle shouldBe null
        }
    }

    @Test
    fun `a blank package name is no name at all`() {
        listOf("", "   ").forEach { blank ->
            deriveAppDetailsDisplay(arguments(packageName = blank)).title shouldBe null
        }
    }
}
