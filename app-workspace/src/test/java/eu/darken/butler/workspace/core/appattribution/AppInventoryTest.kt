package eu.darken.butler.workspace.core.appattribution

import eu.darken.butler.common.pkgs.attribution.AppFolder
import eu.darken.butler.common.pkgs.container.ArchivedPkg
import eu.darken.butler.common.pkgs.container.LibraryPkg
import eu.darken.butler.common.pkgs.container.NormalPkg
import eu.darken.butler.common.pkgs.container.UninstalledPkg
import eu.darken.butler.common.pkgs.features.Installed
import eu.darken.butler.common.user.UserHandle2
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class AppInventoryTest : BaseTest() {

    private inline fun <reified T : Installed> pkg(name: String, user: Int): T = mockk {
        every { packageName } returns name
        every { userHandle } returns UserHandle2(user)
    }

    @Test
    fun `installed and archived apps get their app badge`() {
        val normal = pkg<NormalPkg>("com.normal", 0)
        val archived = pkg<ArchivedPkg>("com.archived", 0)
        val inventory = AppInventory.from(listOf(normal, archived), currentUserId = 0)

        inventory.attribute(AppFolder("com.normal", 0)) shouldBe AppAttribution.App(normal)
        inventory.attribute(AppFolder("com.archived", 0)) shouldBe AppAttribution.App(archived)
    }

    @Test
    fun `uninstalled and unknown packages are ghosts for the current user`() {
        val inventory = AppInventory.from(
            listOf(pkg<NormalPkg>("com.normal", 0), pkg<UninstalledPkg>("com.gone", 0)),
            currentUserId = 0,
        )

        inventory.attribute(AppFolder("com.gone", 0)) shouldBe AppAttribution.Ghost("com.gone")
        inventory.attribute(AppFolder("com.unknown", 0)) shouldBe AppAttribution.Ghost("com.unknown")
    }

    @Test
    fun `the match is per user`() {
        val inventory = AppInventory.from(
            listOf(pkg<NormalPkg>("com.example", 0), pkg<NormalPkg>("com.other", 10)),
            currentUserId = 0,
        )

        inventory.attribute(AppFolder("com.example", 10)) shouldBe AppAttribution.Ghost("com.example")
    }

    @Test
    fun `users whose apps were not enumerated get no ghosts`() {
        val inventory = AppInventory.from(listOf(pkg<NormalPkg>("com.example", 0)), currentUserId = 0)

        inventory.attribute(AppFolder("com.example", 10)) shouldBe null
    }

    @Test
    fun `shared libraries listed for a profile do not make it enumerated`() {
        val library = pkg<LibraryPkg>("com.library", 10)
        val inventory = AppInventory.from(listOf(pkg<NormalPkg>("com.example", 0), library), currentUserId = 0)

        inventory.attribute(AppFolder("com.unknown", 10)) shouldBe null
        inventory.attribute(AppFolder("com.library", 10)) shouldBe AppAttribution.App(library)
    }

    @Test
    fun `folders without an owning user resolve against the current user but never ghost`() {
        val normal = pkg<NormalPkg>("com.example", 10)
        val inventory = AppInventory.from(
            listOf(normal, pkg<UninstalledPkg>("com.gone", 10)),
            currentUserId = 10,
        )

        inventory.attribute(AppFolder("com.example", null)) shouldBe AppAttribution.App(normal)
        inventory.attribute(AppFolder("com.gone", null)) shouldBe null
        inventory.attribute(AppFolder("com.unknown", null)) shouldBe null
    }
}
