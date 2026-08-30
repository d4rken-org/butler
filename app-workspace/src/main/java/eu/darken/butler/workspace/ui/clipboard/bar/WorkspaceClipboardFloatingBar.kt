package eu.darken.butler.workspace.ui.clipboard.bar

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.ui.clipboard.mockFileLookup
import eu.darken.butler.workspace.ui.floatingbar.BarAnimation
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.BarScrollBehavior
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarScope
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarStackState
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * Packages [ClipboardBar] as a floating bar: it owns the visibility rule, the scroll behaviour and
 * the animation, so every workspace's clipboard bar behaves the same way.
 *
 * [key] has no default because it is a per-workspace persistence contract: sharing one literal
 * would tie two workspaces' stored collapse fractions to each other.
 */
@Composable
fun FloatingBarScope.WorkspaceClipboardFloatingBar(
    key: String,
    workspaceType: Workspace.Type,
    clipboardEntries: List<ClipboardClip>,
    onAction: (ClipboardBarAction) -> Unit,
    initialExpanded: Boolean = false,
) {
    FloatingBar(
        key = key,
        visible = clipboardEntries.isNotEmpty(),
        scrollBehavior = BarScrollBehavior.VanishOnScroll,
        animation = BarAnimation.Bouncy,
    ) {
        ClipboardBar(
            workspaceType = workspaceType,
            initialExpanded = initialExpanded,
            clipboardEntries = clipboardEntries,
            onPasteClick = { onAction(ClipboardBarAction.Paste(it)) },
            onRemoveClick = { onAction(ClipboardBarAction.Remove(it)) },
            onEntryClick = { onAction(ClipboardBarAction.ShowInfo(it)) },
            onClearAll = { onAction(ClipboardBarAction.ClearAll) },
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceClipboardFloatingBarPreview() {
    PreviewWrapper {
        val stackState = rememberFloatingBarStackState(position = BarPosition.BOTTOM)
        FloatingBarStack(
            state = stackState,
            position = BarPosition.BOTTOM,
            bars = {
                WorkspaceClipboardFloatingBar(
                    key = "clipboard",
                    workspaceType = Workspace.Type.EXPLORER,
                    clipboardEntries = listOf(
                        ClipboardClip.Paths(
                            origin = Workspace.Id(),
                            mode = ClipboardClip.Paths.Mode.COPY,
                            paths = listOf(mockFileLookup("/storage/emulated/0/Documents/report.pdf")),
                            clippedAt = Clock.System.now() - 2.minutes,
                        ),
                    ),
                    onAction = {},
                )
            },
        )
    }
}
