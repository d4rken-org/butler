package eu.darken.butler.common.adb.service

import android.os.Parcelable
import eu.darken.butler.common.BuildConfigWrap
import kotlinx.parcelize.Parcelize

@Parcelize
data class AdbHostOptions(
    val isDebug: Boolean = BuildConfigWrap.DEBUG,
    val isTrace: Boolean = false,
    val recorderPath: String? = null,
    /**
     * Encoded `IpcContract.HostIdentity` of the app installation that launched the host. Shizuku has
     * no init arguments, so this rides along with the initial options push; the host keeps the FIRST
     * one it ever receives, which makes it a launch stamp rather than a settable value.
     *
     * APPEND new fields, never insert: this crosses the binder as a parcelable, so field order is the
     * wire format.
     */
    val hostIdentity: String? = null,
) : Parcelable