package eu.darken.butler.workspace.core.clipboard

import eu.darken.butler.common.files.APath
import eu.darken.butler.workspace.core.Workspace
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

sealed interface ClipboardClip {
    val id: Uuid
    val origin: Workspace.Id
    val clippedAt: Instant

    data class Paths(
        override val id: Uuid = Uuid.Companion.random(),
        override val clippedAt: Instant = Clock.System.now(),
        override val origin: Workspace.Id,
        val mode: Mode,
        val paths: List<APath>,
    ) : ClipboardClip {
        enum class Mode {
            COPY,
            CUT,
        }
    }
}