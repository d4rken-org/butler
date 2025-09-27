package eu.darken.butler.workspace.core.clipboard

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.getQuantityString2
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.Workspace
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

sealed interface ClipboardClip {
    val id: Uuid
    val origin: Workspace.Id
    val clippedAt: Instant
    val title: CaString
    val description: CaString

    data class Paths(
        override val id: Uuid = Uuid.random(),
        override val clippedAt: Instant = Clock.System.now(),
        override val origin: Workspace.Id,
        val mode: Mode,
        val paths: List<APath>,
    ) : ClipboardClip {
        override val title: CaString
            get() = caString {
                it.getQuantityString2(
                    when (mode) {
                        Mode.COPY -> R.plurals.clipboard_paths_title_copy
                        Mode.CUT -> R.plurals.clipboard_paths_title_cut
                    },
                    paths.size,
                    paths.size
                )
            }

        override val description: CaString
            get() = caString {
                it.getQuantityString2(
                    when (mode) {
                        Mode.COPY -> R.plurals.clipboard_paths_description_copy
                        Mode.CUT -> R.plurals.clipboard_paths_description_cut
                    },
                    paths.size,
                    paths.size, paths.first().parent?.userReadablePath?.get(it) ?: "?"
                )
            }

        enum class Mode {
            COPY,
            CUT,
        }
    }
}