package eu.darken.butler.workspace.ui.workspaces

import android.content.Context
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.mockk
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * [asPaneInfo] is the projection the paused placeholder renders through, so it is a user-facing
 * title consumer: it has to show the same name the rail and the tab manager show, or the middle of
 * the screen contradicts the chrome around it while both are visible.
 */
class WorkspacePaneInfoTest : BaseTest() {

    private val context: Context = mockk()

    private fun pausedInfo(
        customTitle: String? = null,
        title: String = "/sdcard/Download",
    ) = Workspace.Info(
        id = Workspace.Id(),
        type = Workspace.Type.EXPLORER,
        title = title.toCaString(),
        subtitle = "Storage".toCaString(),
        lifecycleState = Workspace.LifecycleState.Paused(),
        customTitle = customTitle,
    )

    @Test
    fun `a custom name is projected to the pane instead of the automatic title`() {
        val pane = pausedInfo(customTitle = "Holiday photos").asPaneInfo()

        pane.title.get(context) shouldBe "Holiday photos"
        // The derived subtitle belongs to the workspace, renaming must not disturb it
        pane.subtitle!!.get(context) shouldBe "Storage"
    }

    @Test
    fun `without a custom name the pane shows the automatic title`() {
        val info = pausedInfo(title = "/sdcard/Pictures")

        val pane = info.asPaneInfo()

        pane.title shouldBeSameInstanceAs info.title
        pane.title.get(context) shouldBe "/sdcard/Pictures"
    }

    @Test
    fun `clearing a custom name reveals the latest automatic title, not a stale one`() {
        val named = pausedInfo(customTitle = "Holiday photos", title = "/sdcard/Download")
        named.asPaneInfo().title.get(context) shouldBe "Holiday photos"

        // The derived title moves on underneath while the custom name is the one on screen
        val movedOn = named.copy(title = "/sdcard/Pictures".toCaString())
        val cleared = movedOn.copy(customTitle = null)

        cleared.asPaneInfo().title shouldBeSameInstanceAs movedOn.title
        cleared.asPaneInfo().title.get(context) shouldBe "/sdcard/Pictures"
    }
}
