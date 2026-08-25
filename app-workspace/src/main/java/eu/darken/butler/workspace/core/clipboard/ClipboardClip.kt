package eu.darken.butler.workspace.core.clipboard

import android.content.Context
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
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
        val paths: List<APathLookup<*>>,
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
                val locations = sourceLocations(it)
                when {
                    paths.size == 1 -> paths.single().userReadablePath.get(it)
                    locations.size > 1 -> {
                        val locationsPhrase = it.getQuantityString2(
                            R.plurals.clipboard_paths_locations,
                            locations.size,
                            locations.size,
                        )
                        it.getQuantityString2(
                            when (mode) {
                                Mode.COPY -> R.plurals.clipboard_paths_description_copy_multi
                                Mode.CUT -> R.plurals.clipboard_paths_description_cut_multi
                            },
                            paths.size,
                            paths.size, locationsPhrase
                        )
                    }

                    else -> it.getQuantityString2(
                        when (mode) {
                            Mode.COPY -> R.plurals.clipboard_paths_description_copy
                            Mode.CUT -> R.plurals.clipboard_paths_description_cut
                        },
                        paths.size,
                        paths.size, locations.first()
                    )
                }
            }

        /**
         * The distinct directories the clipped paths were taken from. A path at the filesystem
         * root has no parent and reads as "/", the same as an item directly below the root, so
         * both count as one location.
         */
        fun sourceLocations(context: Context): List<String> = paths
            .map { path -> path.parent?.userReadablePath?.get(context) ?: "/" }
            .distinct()

        enum class Mode {
            COPY,
            CUT,
        }
    }

    data class Text(
        override val id: Uuid = Uuid.random(),
        override val clippedAt: Instant = Clock.System.now(),
        override val origin: Workspace.Id,
        val content: String,
        val sourcePath: APath<*>? = null,
    ) : ClipboardClip {
        override val title: CaString
            get() = R.string.clipboard_text_title.toCaString()

        override val description: CaString
            get() = caString {
                it.getQuantityString2(
                    R.plurals.clipboard_text_description,
                    content.length,
                    content.length,
                )
            }

        val preview: String
            get() = content.take(50).replace('\n', ' ') + if (content.length > 50) "…" else ""

        companion object {
            const val MAX_SIZE_BYTES = 256 * 1024 // 256 KB for in-memory storage
        }
    }
}