package eu.darken.butler.explorer.ui.explorer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.FolderOpen
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import eu.darken.butler.explorer.R
import kotlinx.coroutines.delay

@Composable
fun EmptyFolderState(
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.TwoTone.FolderOpen,
    title: String = "This folder is empty",
    caption: String? = null
) {
    var visible by remember { mutableStateOf(false) }

    // Get caption from resources or use provided one
    val displayCaption = caption ?: getFunnyCaption()

    // Fade in animation
    LaunchedEffect(Unit) {
        delay(100)
        visible = true
    }

    // Gentle floating animation for icon
    val infiniteTransition = rememberInfiniteTransition(label = "floating")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(600)),
            exit = fadeOut()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(32.dp)
            ) {
                // Animated icon with background
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(24.dp)
                        )
                        .scale(scale)
                        .alpha(alpha),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(56.dp)
                    )
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 24.dp)
                )

                // Static caption (no animation to prevent jumping)
                Text(
                    text = displayCaption,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun getFunnyCaption(): String {
    val captions = listOf(
        stringResource(R.string.explorer_empty_folder_caption_1),
        stringResource(R.string.explorer_empty_folder_caption_2),
        stringResource(R.string.explorer_empty_folder_caption_3),
        stringResource(R.string.explorer_empty_folder_caption_4),
        stringResource(R.string.explorer_empty_folder_caption_5),
        stringResource(R.string.explorer_empty_folder_caption_6),
        stringResource(R.string.explorer_empty_folder_caption_7),
        stringResource(R.string.explorer_empty_folder_caption_8),
        stringResource(R.string.explorer_empty_folder_caption_9),
        stringResource(R.string.explorer_empty_folder_caption_10),
        stringResource(R.string.explorer_empty_folder_caption_11),
        stringResource(R.string.explorer_empty_folder_caption_12),
        stringResource(R.string.explorer_empty_folder_caption_13),
        stringResource(R.string.explorer_empty_folder_caption_14),
        stringResource(R.string.explorer_empty_folder_caption_15),
    )
    return captions.random()
}

@Preview(showBackground = true)
@Composable
fun EmptyFolderStatePreview() {
    MaterialTheme {
        EmptyFolderState()
    }
}

@Preview(showBackground = true)
@Composable
fun EmptyFolderStateCustomPreview() {
    MaterialTheme {
        EmptyFolderState(
            title = "No search results",
            caption = "Try a different search term"
        )
    }
}