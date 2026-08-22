package eu.darken.butler.common.root.service.internal

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class RootHostInitArgs(
    val packageName: String,
    val pairingCode: String,
    val waitForDebugger: Boolean = false,
    val isDebug: Boolean = false,
    val isTrace: Boolean = false,
    val recorderPath: String? = null,
    /**
     * Encoded `IpcContract.HostIdentity` of the app installation launching this host, echoed back by
     * the host's `checkBase()` so a host that survived an in-place app update can be detected.
     *
     * APPEND new fields, never insert: this is marshalled through a raw [android.os.Parcel], so field
     * order is the wire format.
     */
    val hostIdentity: String? = null,
) : Parcelable