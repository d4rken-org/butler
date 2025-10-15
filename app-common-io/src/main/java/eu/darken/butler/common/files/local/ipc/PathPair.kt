package eu.darken.butler.common.files.local.ipc

import android.os.Parcelable
import eu.darken.butler.common.files.LocalPath
import kotlinx.parcelize.Parcelize

/**
 * Parcelable wrapper for source→destination path pairs.
 * Used in Copy and Move operation results.
 *
 * Note: Kotlin's Pair is not Parcelable, so we need this wrapper.
 */
@Parcelize
data class PathPair(
    val source: LocalPath,
    val destination: LocalPath,
) : Parcelable
