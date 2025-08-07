package eu.darken.butler.workspace.ui.manager

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.AddCircle
import androidx.compose.material.icons.twotone.Workspaces
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.WorkspaceAction

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WorkspaceButton(
    modifier: Modifier = Modifier,
    state: WorkspaceButtonViewModel.State?,
    containerColor: Color? = null,
    contentColor: Color? = null,
    onAction: (WorkspaceAction) -> Unit,
    onNavToWorkspaceManager: () -> Unit,
) {
    val (normalAction, longAction) = if (state?.isButtonFlipped == true) {
        // Flipped mode: normal click adds workspace, long click opens manager
        { onAction(WorkspaceAction.Create()) } to { onNavToWorkspaceManager() }
    } else {
        // Normal mode: normal click opens manager, long click adds workspace
        { onNavToWorkspaceManager() } to { onAction(WorkspaceAction.Create()) }
    }

    val icon = if (state?.isButtonFlipped == true) {
        Icons.TwoTone.AddCircle
    } else {
        Icons.TwoTone.Workspaces
    }

    Box(modifier = modifier) {
        // Button background
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(containerColor ?: MaterialTheme.colorScheme.tertiaryContainer)
                .combinedClickable(
                    onClick = normalAction,
                    onLongClick = longAction
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor ?: MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(24.dp)
            )
        }

        // Badge showing workspace count (top-left)
        if (state?.workspaceCount != null && state.workspaceCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-8).dp, y = (-8).dp)
                    .size(16.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (state.workspaceCount > 9) "9+" else state.workspaceCount.toString(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.sp,
                    lineHeight = 9.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(bottom = 1.dp)
                )
            }
        }

        // Badge showing operations count (top-right)
        if (state?.operationsCount != null && state.operationsCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 8.dp, y = (-8).dp)
                    .size(16.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (state.operationsCount > 9) "9+" else state.operationsCount.toString(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 9.sp,
                    lineHeight = 9.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(bottom = 1.dp)
                )
            }
        }

        // Badge showing attention count (bottom-right)
        if (state?.attentionCount != null && state.attentionCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 8.dp, y = 8.dp)
                    .size(16.dp)
                    .background(
                        color = MaterialTheme.colorScheme.error,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (state.attentionCount > 9) "9+" else state.attentionCount.toString(),
                    color = MaterialTheme.colorScheme.onError,
                    fontSize = 9.sp,
                    lineHeight = 9.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(bottom = 1.dp)
                )
            }
        }
    }
}

@Preview2
@Composable
private fun WorkspaceButtonPreview() {
    PreviewWrapper {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            // No workspaces
            WorkspaceButton(
                state = WorkspaceButtonViewModel.State(
                    workspaceCount = 0,
                    isButtonFlipped = false,
                    operationsCount = 0,
                    attentionCount = 0,
                ),
                onAction = {},
                onNavToWorkspaceManager = {}
            )

            // Single workspace
            WorkspaceButton(
                state = WorkspaceButtonViewModel.State(
                    workspaceCount = 1,
                    isButtonFlipped = false,
                    operationsCount = 0,
                    attentionCount = 0,
                ),
                onAction = {},
                onNavToWorkspaceManager = {}
            )

            // Multiple workspaces with operations
            WorkspaceButton(
                state = WorkspaceButtonViewModel.State(
                    workspaceCount = 3,
                    isButtonFlipped = false,
                    operationsCount = 2,
                    attentionCount = 0,
                ),
                onAction = {},
                onNavToWorkspaceManager = {}
            )

            // All badges active
            WorkspaceButton(
                state = WorkspaceButtonViewModel.State(
                    workspaceCount = 5,
                    isButtonFlipped = false,
                    operationsCount = 7,
                    attentionCount = 1,
                ),
                onAction = {},
                onNavToWorkspaceManager = {}
            )

            // Max badge values
            WorkspaceButton(
                state = WorkspaceButtonViewModel.State(
                    workspaceCount = 12,
                    isButtonFlipped = true,
                    operationsCount = 15,
                    attentionCount = 10,
                ),
                onAction = {},
                onNavToWorkspaceManager = {}
            )
        }
    }
}