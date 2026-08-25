package eu.darken.butler.explorer.ui.explorer.items

import android.content.Context
import eu.darken.butler.common.files.smb.SmbEndpointState
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerItem

/** `host/share`, or `host/share [192.168.1.50]` once the probe knows where that is. */
internal fun ExplorerItem.Storage.Network.endpointLabel(context: Context): String {
    val endpointText = subtitle.get(context)
    val address = endpoint.address ?: return endpointText
    return context.getString(R.string.explorer_network_endpoint_with_address_format, endpointText, address)
}

/** A credential problem outranks reachability: it is the one the user can act on. */
internal fun ExplorerItem.Storage.Network.statusLabel(context: Context): String = when (status) {
    ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED -> {
        context.getString(R.string.explorer_network_sign_in_required_label)
    }

    ExplorerItem.Storage.Network.Status.AVAILABLE -> when (endpoint.reachability) {
        SmbEndpointState.Reachability.CHECKING -> context.getString(R.string.explorer_network_status_checking_label)
        SmbEndpointState.Reachability.REACHABLE -> context.getString(R.string.explorer_network_status_available_label)
        SmbEndpointState.Reachability.UNREACHABLE -> {
            context.getString(R.string.explorer_network_status_unavailable_label)
        }
    }
}
