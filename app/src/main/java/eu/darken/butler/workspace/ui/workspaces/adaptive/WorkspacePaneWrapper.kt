package eu.darken.butler.workspace.ui.workspaces.adaptive

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import eu.darken.butler.templates.ui.WorkspaceTab

@Composable
internal fun WorkspacePaneWrapper(
    modifier: Modifier = Modifier.Companion,
    tab: WorkspaceTab,
    isFocused: Boolean,
    showFocusBorder: Boolean,
    onFocus: () -> Unit,
    paneNumber: Int?,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clickable { onFocus() }
            .then(
                if (showFocusBorder) {
                    if (isFocused) {
                        Modifier.Companion.border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = MaterialTheme.shapes.medium,
                        )
                    } else {
                        Modifier.Companion.border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            shape = MaterialTheme.shapes.medium,
                        )
                    }
                } else {
                    Modifier.Companion
                }
            )
            .padding(if (showFocusBorder) 2.dp else 0.dp),
    ) {
        content()

        paneNumber?.let {
            Surface(
                modifier = Modifier.Companion
                    .align(Alignment.Companion.Center)
                    .zIndex(10f),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                tonalElevation = 8.dp,
            ) {
                Text(
                    text = it.toString(),
                    modifier = Modifier.Companion.padding(horizontal = 24.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}