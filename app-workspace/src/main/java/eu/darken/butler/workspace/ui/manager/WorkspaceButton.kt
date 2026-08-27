package eu.darken.butler.workspace.ui.manager

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.visible
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.common.compose.ButlerMascot
import eu.darken.butler.common.compose.ButlerMascotMode
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonDefaults.sizeCompact
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonDefaults.sizeDefault


@Composable
@SuppressLint("ModifierParameter")
fun WorkspaceButton(
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    buttonSize: Dp = sizeDefault,
    currentWorkspaceId: Workspace.Id? = null,
    mascotVariant: ButlerMascotMode = ButlerMascotMode.Animated.RandomCycling(),
) {
    val provider = LocalWorkspaceButtonProvider.current
    val state = provider?.state?.collectAsState(initial = null)?.value

    var expanded by remember { mutableStateOf(false) }
    var showCloseAllDialog by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        @Suppress("COMPOSE_APPLIER_CALL_MISMATCH")
        BoxWithConstraints(
            modifier = Modifier
                .size(buttonSize)
                .testTag(WorkspaceButtonDefaults.TEST_TAG)
                .clip(RoundedCornerShape(8.dp))
                .background(containerColor ?: MaterialTheme.colorScheme.tertiaryContainer)
                .combinedClickable(
                    onClick = { expanded = true },
                    onLongClick = {
                        provider?.navToWorkspaceManager()
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            val iconSize = minOf(maxWidth, maxHeight) * 0.8f

            ButlerMascot(
                modifier = Modifier.size(iconSize),
                variant = mascotVariant,
            )
        }

        WorkspaceButtonMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            state = state,
            currentWorkspaceId = currentWorkspaceId,
            provider = provider,
            onCloseAllRequested = { showCloseAllDialog = true },
        )

        // Close all confirmation dialog
        CloseWorkspacesDialog(
            visible = showCloseAllDialog,
            workspaceCount = state?.workspaceCount ?: 0,
            hasUnsavedChanges = state?.hasUnsavedChanges == true,
            onDismiss = { showCloseAllDialog = false },
            onConfirm = {
                showCloseAllDialog = false
                provider?.executeWorkspaceAction(WorkspaceAction.CloseAll)
            }
        )

        // Badge showing workspace count (top-left)
        if (state?.workspaceCount != null && state.workspaceCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .run {
                        when {
                            buttonSize >= sizeDefault -> this
                                .offset(x = (-6).dp, y = (-6).dp)
                                .size(14.dp)
                                .visible(true)
                            buttonSize >= sizeCompact -> this
                                .offset(x = (-4).dp, y = (-4).dp)
                                .size(12.dp)
                                .visible(true)
                            else -> this
                                .visible(false)
                        }
                    }
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (state.workspaceCount > 9) "9+" else state.workspaceCount.toString(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = if (buttonSize >= sizeDefault) 9.sp else 7.sp,
                    lineHeight = if (buttonSize >= sizeDefault) 9.sp else 7.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(bottom = if (buttonSize >= sizeDefault) 1.dp else 0.dp)
                )
            }
        }

        // Badge showing operations count (top-right)
        if (state?.operationsCount != null && state.operationsCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .run {
                        when {
                            buttonSize >= sizeDefault -> this
                                .offset(x = 6.dp, y = (-6).dp)
                                .size(14.dp)
                                .visible(true)
                            buttonSize >= sizeCompact -> this
                                .offset(x = 4.dp, y = (-4).dp)
                                .size(12.dp)
                                .visible(true)
                            else -> this
                                .visible(false)
                        }
                    }
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (state.operationsCount > 9) "9+" else state.operationsCount.toString(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = if (buttonSize >= sizeDefault) 9.sp else 7.sp,
                    lineHeight = if (buttonSize >= sizeDefault) 9.sp else 7.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(bottom = if (buttonSize >= sizeDefault) 1.dp else 0.dp)
                )
            }
        }

        // Badge showing attention count (bottom-right)
        if (state?.attentionCount != null && state.attentionCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .run {
                        when {
                            buttonSize >= sizeDefault -> this
                                .offset(x = 6.dp, y = 6.dp)
                                .size(14.dp)
                                .visible(true)
                            buttonSize >= sizeCompact -> this
                                .offset(x = 4.dp, y = 4.dp)
                                .size(12.dp)
                                .visible(true)
                            else -> this
                                .visible(false)
                        }
                    }
                    .background(
                        color = MaterialTheme.colorScheme.error,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (state.attentionCount > 9) "9+" else state.attentionCount.toString(),
                    color = MaterialTheme.colorScheme.onError,
                    fontSize = if (buttonSize >= sizeDefault) 9.sp else 7.sp,
                    lineHeight = if (buttonSize >= sizeDefault) 9.sp else 7.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(bottom = if (buttonSize >= sizeDefault) 1.dp else 0.dp)
                )
            }
        }
    }
}

object WorkspaceButtonDefaults {
    val sizeDefault = 48.dp
    val sizeCompact = 40.dp
    const val TEST_TAG = "workspace.button"
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceButtonSizesPreview() {
    CompositionLocalProvider(
        LocalWorkspaceButtonProvider provides FakeWorkspaceButtonProvider(
            WorkspaceButtonViewModel.State(
                workspaceCount = 3,
                operationsCount = 2,
                attentionCount = 1,
            )
        )
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            WorkspaceButton(buttonSize = 32.dp)
            WorkspaceButton(buttonSize = sizeCompact)
            WorkspaceButton(buttonSize = sizeDefault)
            WorkspaceButton(buttonSize = 72.dp)
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceButtonEmptyBadgesPreview() {
    CompositionLocalProvider(
        LocalWorkspaceButtonProvider provides FakeWorkspaceButtonProvider(
            WorkspaceButtonViewModel.State(
                workspaceCount = 0,
                operationsCount = 0,
                attentionCount = 0,
            )
        )
    ) {
        WorkspaceButton(modifier = Modifier.padding(16.dp))
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceButtonOverflowBadgesPreview() {
    CompositionLocalProvider(
        LocalWorkspaceButtonProvider provides FakeWorkspaceButtonProvider(
            WorkspaceButtonViewModel.State(
                workspaceCount = 12,
                operationsCount = 15,
                attentionCount = 10,
            )
        )
    ) {
        WorkspaceButton(modifier = Modifier.padding(16.dp))
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceButtonPositionedPreview() {
    CompositionLocalProvider(
        LocalWorkspaceButtonProvider provides FakeWorkspaceButtonProvider(
            WorkspaceButtonViewModel.State(
                workspaceCount = 5,
                operationsCount = 7,
                attentionCount = 1,
            )
        )
    ) {
        Box(
            modifier = Modifier
                .width(128.dp)
                .height(128.dp)
        ) {
            WorkspaceButton(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 16.dp),
            )
        }
    }
}