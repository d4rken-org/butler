package eu.darken.butler.common.files.local.ipc

import android.os.Parcelable
import eu.darken.butler.common.files.LocalPath
import kotlinx.parcelize.Parcelize

/**
 * Declarative walk configuration that can cross the IPC boundary (closures can't).
 *
 * @param pathDoesNotContain Raw substring excludes (legacy filter, applied per item).
 * @param followSymlinks Follow symlinks-to-directories host-side (cycle-safe).
 * @param excludeSubtrees Subtree roots the host must NOT enter or emit. Used when a delegated
 * walk contains a known route boundary that a different access mode has to handle (e.g. an
 * ISOLATED walk of a removable volume whose `Android/data` needs ROOT).
 */
@Parcelize
data class WalkSpec(
    val pathDoesNotContain: List<String>? = null,
    val followSymlinks: Boolean = false,
    val excludeSubtrees: List<LocalPath>? = null,
) : Parcelable
