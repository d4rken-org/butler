package eu.darken.butler.main.core.shortcuts

import eu.darken.butler.common.files.APath
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Serializable
data class RecentPath(
    @Contextual val id: ShortcutId = Uuid.random(),
    val path: APath,
    val accessCount: Int = 1,
    @Contextual val lastAccessed: Instant = Clock.System.now(),
)

@Serializable
data class LastAccessedPaths(
    val paths: List<RecentPath> = emptyList(),
)

