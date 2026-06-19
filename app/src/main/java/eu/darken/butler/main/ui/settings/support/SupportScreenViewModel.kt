package eu.darken.butler.main.ui.settings.support

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.main.ui.settings.contactForm
import eu.darken.butler.workspace.contracts.bugreport.BugReportArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.createAndFocus
import eu.darken.butler.workspace.ui.workspaces.workspaces
import javax.inject.Inject

@HiltViewModel
class SupportScreenViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val webpageTool: WebpageTool,
    private val workspaceRemote: WorkspaceRemote,
) : ViewModel4(dispatcherProvider, logTag("Settings", "Support", "ViewModel")) {

    fun openUrl(url: String) {
        webpageTool.open(url)
    }

    fun contactSupport() {
        navTo(Nav.Settings.contactForm())
    }

    /** Spawn/focus the unified Bug reports workspace and switch the UI to it. */
    fun openBugReports() = launch {
        log(tag) { "openBugReports()" }
        workspaceRemote.createAndFocus(Workspace.Type.BUG_REPORT, BugReportArguments.Default())
        // Pop the settings stack back to the workspaces screen, which now shows the focused workspace.
        // inclusive = true so we don't leave a duplicate workspaces entry on the back stack.
        navTo(Nav.Main.workspaces(), popUpTo = Nav.Main.workspaces(), inclusive = true)
    }
}
