package eu.darken.butler.apps.core.details.components

import eu.darken.butler.apps.core.details.AppInfo
import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.root.RootManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Whether the components of the inspected app may be toggled at all.
 *
 * [UNSUPPORTED] and [NEEDS_SETUP] are deliberately distinct: the first hides the affordance
 * entirely, the second renders it greyed with a route into the setup screen.
 */
enum class ComponentToggleState { UNSUPPORTED, NEEDS_SETUP, AVAILABLE }

/**
 * The workspace's single, background-resolved answer to "may components be toggled".
 *
 * Owned by core rather than by a ViewModel: the page and the overlay slot are sibling subtrees and
 * both need the same answer, and it must not restart when either of them remounts.
 *
 * Two load-bearing properties:
 * - Shared **eagerly in the workspace scope**, so the probe starts as the workspace loads rather
 *   than when the Components route is first composed.
 * - [state] is **nullable, with `null` meaning "still resolving"**. Consumers gate on
 *   `filterNotNull()`, so the UI never renders a transient "unavailable" frame that flips to
 *   "available" a moment later.
 */
class ComponentToggleAvailability(
    scope: CoroutineScope,
    appInfo: Flow<AppInfo?>,
    rootManager: RootManager,
    adbManager: AdbManager,
    ownPackageName: String,
) {

    val state: StateFlow<ComponentToggleState?> = combine(
        appInfo,
        rootManager.useRoot,
        adbManager.useAdb,
    ) { app, hasRoot, hasAdb ->
        when {
            app == null -> ComponentToggleState.UNSUPPORTED
            // Butler must not disable its own components: DONT_KILL_APP is deliberately omitted, so
            // it would kill itself mid-action, and disabling its launcher activity would leave no
            // in-app route back.
            app.packageName == ownPackageName -> ComponentToggleState.UNSUPPORTED
            // resolveEnabled() is `appEnabled && …`, so while the application is disabled every
            // component already reads DISABLED and no toggle can produce a visible change. Offering
            // one would report success and do nothing.
            !app.isEnabled -> ComponentToggleState.UNSUPPORTED
            hasRoot || hasAdb -> ComponentToggleState.AVAILABLE
            else -> ComponentToggleState.NEEDS_SETUP
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)
}
