package eu.darken.butler.apps.ui.apps

import eu.darken.butler.apps.core.AppsWorkspace
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * The selection count shown in the info bar must always equal the set the action bar operates on,
 * otherwise "55 selected" can coexist with Uninstall targeting a single app.
 */
class AppsSelectionStateTest {

    private val chrome = AppsMockDataProvider.Presets.chromeItem
    private val settings = AppsMockDataProvider.Presets.settingsItem
    private val notes = AppsMockDataProvider.Presets.notesItem

    @Test
    fun `workspace state counts only visible selections`() {
        val state = AppsWorkspace.State.Ready(
            apps = listOf(chrome, settings, notes),
            filteredApps = listOf(chrome),
            selectedAppIds = setOf(chrome.pkg.installId, settings.pkg.installId, notes.pkg.installId),
        )

        state.selectedApps shouldBe listOf(chrome)
        state.selectionCount shouldBe 1
        state.isMultiSelectMode shouldBe true
    }

    @Test
    fun `workspace state leaves selection mode when no selected app is visible`() {
        val state = AppsWorkspace.State.Ready(
            apps = listOf(chrome, settings),
            filteredApps = listOf(settings),
            selectedAppIds = setOf(chrome.pkg.installId),
        )

        state.selectionCount shouldBe 0
        state.isMultiSelectMode shouldBe false
    }

    @Test
    fun `viewmodel state counts only visible selections`() {
        val state = AppsWorkspaceViewModel.State.Ready(
            apps = listOf(chrome),
            selectedAppIds = setOf(chrome.pkg.installId, settings.pkg.installId),
        )

        state.selectedApps shouldBe listOf(chrome)
        state.selectionCount shouldBe 1
        state.isMultiSelectMode shouldBe true
    }

    @Test
    fun `viewmodel state reports full count once everything is visible again`() {
        val state = AppsWorkspaceViewModel.State.Ready(
            apps = listOf(chrome, settings, notes),
            selectedAppIds = setOf(chrome.pkg.installId, settings.pkg.installId),
        )

        state.selectionCount shouldBe 2
        state.userAppsCount shouldBe listOf(chrome, settings, notes).count { !it.isSystemApp }
        state.systemAppsCount shouldBe listOf(chrome, settings, notes).count { it.isSystemApp }
    }
}
