package eu.darken.butler.setup.ui.items

import eu.darken.butler.setup.core.root.RootServiceState
import eu.darken.butler.setup.core.root.RootSetupModule

/** What the root setup card tells the user, resolved once for every surface that renders it. */
enum class RootCardStatus {
    DISABLED,
    CONNECTING,
    CONNECTED,
    NOT_CONNECTED,
    CONNECTION_FAILED,
}

/**
 * The installed-manager lookup only knows a handful of package ids, so it cannot tell an absent
 * root manager from one it does not recognise. The card never keys its wording on it.
 */
fun RootSetupModule.Result?.toCardStatus(): RootCardStatus = when {
    this == null || useRoot != true -> RootCardStatus.DISABLED
    else -> when (serviceState) {
        is RootServiceState.Available -> RootCardStatus.CONNECTED
        is RootServiceState.NotChecked, is RootServiceState.Connecting -> RootCardStatus.CONNECTING
        is RootServiceState.Failed -> RootCardStatus.NOT_CONNECTED
        is RootServiceState.TimedOut -> RootCardStatus.CONNECTION_FAILED
    }
}
