package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.core.label
import kotlinx.coroutines.delay

@Composable
fun WorkspaceSwitchIndicator(
    modifier: Modifier = Modifier,
    info: Workspace.Info,
    position: Int,
    totalWorkspaces: Int,
) {
    // Reset visibility state when workspace ID changes
    var isVisible by remember(info.id) { mutableStateOf(true) }

    // Auto-hide after delay
    LaunchedEffect(info.id) {
        delay(1000)
        isVisible = false
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(
            animationSpec = tween(durationMillis = 50)
        ) + scaleIn(
            initialScale = 0.8f,
            animationSpec = tween(durationMillis = 50)
        ),
        exit = fadeOut(
            animationSpec = tween(durationMillis = 100)
        ) + scaleOut(
            targetScale = 0.9f,
            animationSpec = tween(durationMillis = 100)
        ),
        modifier = modifier,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 300.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = 8.dp
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                // Row 1: Icon + Type label (left) | Position (right)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        modifier = Modifier.size(18.dp),
                        imageVector = info.type.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = info.type.label.asComposable(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(
                            id = eu.darken.butler.common.R.string.common_x_of_y_label,
                            position,
                            totalWorkspaces,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }

                Spacer(modifier = Modifier.padding(vertical = 2.dp))
                // Row 2: Title
                Text(
                    text = info.displayTitle.asComposable(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )

                // Row 3: Subtitle (if present)
                val subtitle = info.subtitle?.asComposable()
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceSwitchIndicatorExplorerPreview() {
    WorkspaceSwitchIndicator(
        info = Workspace.Info(
            id = Workspace.Id(),
            type = Workspace.Type.EXPLORER,
            title = "/storage/emulated/0/Download".toCaString(),
            subtitle = "File browser".toCaString(),
        ),
        position = 2,
        totalWorkspaces = 5,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceSwitchIndicatorSearcherPreview() {
    WorkspaceSwitchIndicator(
        info = Workspace.Info(
            id = Workspace.Id(),
            type = Workspace.Type.SEARCHER,
            title = "*.mp3".toCaString(),
            subtitle = "Searching in /sdcard".toCaString(),
        ),
        position = 1,
        totalWorkspaces = 3,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceSwitchIndicatorEditorNoSubtitlePreview() {
    WorkspaceSwitchIndicator(
        info = Workspace.Info(
            id = Workspace.Id(),
            type = Workspace.Type.EDITOR,
            title = "config.json".toCaString(),
            subtitle = null,
        ),
        position = 3,
        totalWorkspaces = 10,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceSwitchIndicatorLongTitlePreview() {
    WorkspaceSwitchIndicator(
        info = Workspace.Info(
            id = Workspace.Id(),
            type = Workspace.Type.EXPLORER,
            title = "/storage/emulated/0/Android/data/com.example.app/files/documents/reports".toCaString(),
            subtitle = "Very long subtitle that should be truncated with ellipsis".toCaString(),
        ),
        position = 5,
        totalWorkspaces = 8,
    )
}
