package eu.darken.butler.explorer.core

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.workspace.contracts.explorer.ExplorerStartTarget
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ExplorerStartLocationTest : BaseTest() {

    private val downloads = LocalPath.build("/sdcard/Download")
    private val pictures = LocalPath.build("/sdcard/Pictures")

    @Test
    fun `an explicit path wins over a parked target`() {
        explorerStartTarget(
            startPath = downloads,
            startTarget = ExplorerStartTarget.TRASH,
            defaultStartLocation = DefaultStartLocation.Device,
        ) shouldBe ExplorerNavigation.Target.Directory(downloads)
    }

    @Test
    fun `a parked home target restores to home`() {
        explorerStartTarget(
            startPath = null,
            startTarget = ExplorerStartTarget.HOME,
            defaultStartLocation = null,
        ) shouldBe ExplorerNavigation.Target.Home
    }

    @Test
    fun `a parked device target restores to device`() {
        explorerStartTarget(
            startPath = null,
            startTarget = ExplorerStartTarget.DEVICE,
            defaultStartLocation = null,
        ) shouldBe ExplorerNavigation.Target.Device
    }

    @Test
    fun `a parked trash target restores to the trash root`() {
        explorerStartTarget(
            startPath = null,
            startTarget = ExplorerStartTarget.TRASH,
            defaultStartLocation = null,
        ) shouldBe ExplorerNavigation.Target.Trash.Root
    }

    @Test
    fun `a parked target wins over the default start location`() {
        explorerStartTarget(
            startPath = null,
            startTarget = ExplorerStartTarget.TRASH,
            defaultStartLocation = DefaultStartLocation.Directory(pictures),
        ) shouldBe ExplorerNavigation.Target.Trash.Root
    }

    @Test
    fun `arguments without a location use the default device setting`() {
        explorerStartTarget(
            startPath = null,
            startTarget = null,
            defaultStartLocation = DefaultStartLocation.Device,
        ) shouldBe ExplorerNavigation.Target.Device
    }

    @Test
    fun `arguments without a location use the default directory setting`() {
        explorerStartTarget(
            startPath = null,
            startTarget = null,
            defaultStartLocation = DefaultStartLocation.Directory(pictures),
        ) shouldBe ExplorerNavigation.Target.Directory(pictures)
    }

    @Test
    fun `arguments without a location use the default home setting`() {
        explorerStartTarget(
            startPath = null,
            startTarget = null,
            defaultStartLocation = DefaultStartLocation.Home,
        ) shouldBe ExplorerNavigation.Target.Home
    }

    @Test
    fun `legacy arguments with an unset setting still start at home`() {
        explorerStartTarget(
            startPath = null,
            startTarget = null,
            defaultStartLocation = null,
        ) shouldBe ExplorerNavigation.Target.Home
    }

    @Test
    fun `navigation targets map onto their persistable stand-in`() {
        ExplorerNavigation.Target.Home.asStartTarget shouldBe ExplorerStartTarget.HOME
        ExplorerNavigation.Target.Device.asStartTarget shouldBe ExplorerStartTarget.DEVICE
        ExplorerNavigation.Target.Trash.Root.asStartTarget shouldBe ExplorerStartTarget.TRASH
        ExplorerNavigation.Target.Directory(downloads).asStartTarget shouldBe null
    }

    @Test
    fun `a persisted target maps back onto the navigation target it names`() {
        ExplorerStartTarget.entries.forEach { target ->
            target.asNavigationTarget.asStartTarget shouldBe target
        }
    }
}
