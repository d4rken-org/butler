package eu.darken.butler.workspace.ui.clipboard

import androidx.compose.runtime.Stable
import eu.darken.butler.workspace.core.clipboard.ClipboardClip

@Stable
data class ClipboardDisplayState(
    val entries: List<ClipboardClip> = emptyList(),
)
