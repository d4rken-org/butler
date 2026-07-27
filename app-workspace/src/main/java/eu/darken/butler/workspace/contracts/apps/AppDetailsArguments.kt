package eu.darken.butler.workspace.contracts.apps

import eu.darken.butler.workspace.core.Workspace
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Arguments for launching an App Details workspace.
 * Implements ArgumentsWithCaller to support modal rendering when callerWorkspaceId is set.
 *
 * This is a detail/informational workspace (not a picker), so it defaults to PANE_LOCAL
 * presentation mode, allowing it to render as an overlay within the parent's pane on tablets
 * while appearing as a full-screen modal on phones.
 *
 * @param packageName The package name of the app to display details for
 * @param initialTab The tab to show initially (defaults to OVERVIEW)
 * @param callerWorkspaceId If set, this workspace will render as a modal. Session-transient:
 * caller relationships are not persisted because sub-workspaces are excluded from session
 * save and a restored caller could not receive results anyway.
 */
@Serializable
@Parcelize
data class AppDetailsArguments(
    val packageName: String,
    val initialTab: DetailTab = DetailTab.OVERVIEW,
    @Transient override val callerWorkspaceId: Workspace.Id? = null,
) : Workspace.ArgumentsWithCaller {
    @IgnoredOnParcel
    override val type: Workspace.Type get() = Workspace.Type.APP_DETAILS

    /**
     * These arguments describe the whole workspace - a package and the tab it shows - so an app
     * details overlay can be released together with the tab that opened it and rebuilt exactly as it
     * was. It owes its caller no result, so nobody is waiting on it either.
     */
    @IgnoredOnParcel
    override val pausableAsChild: Boolean get() = true
}
