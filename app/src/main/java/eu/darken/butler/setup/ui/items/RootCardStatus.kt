package eu.darken.butler.setup.ui.items

import eu.darken.butler.setup.core.root.RootServiceState
import eu.darken.butler.setup.core.root.RootSetupModule

/** What the root setup card tells the user, resolved once for every surface that renders it. */
enum class RootCardStatus {
    DISABLED,
    CONNECTING,
    CONNECTED,
    NOT_INSTALLED,
    NOT_CONNECTED,
    CONNECTION_FAILED,
}

/**
 * The installed-manager lookup only knows a handful of package ids, so it says nothing about a
 * device whose root manager is not one of them. It is consulted after the probe concluded it failed,
 * never as a reason to contradict a service that is answering.
 */
fun RootSetupModule.Result?.toCardStatus(): RootCardStatus = when {
    this == null || useRoot != true -> RootCardStatus.DISABLED
    else -> when (serviceState) {
        is RootServiceState.Available -> RootCardStatus.CONNECTED
        is RootServiceState.NotChecked, is RootServiceState.Connecting -> RootCardStatus.CONNECTING
        is RootServiceState.Failed -> if (isInstalled) RootCardStatus.NOT_CONNECTED else RootCardStatus.NOT_INSTALLED
        is RootServiceState.TimedOut -> RootCardStatus.CONNECTION_FAILED
    }
}
