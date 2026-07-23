package eu.darken.butler.saver.ui.saver

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.material.icons.twotone.MoveToInbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.R as CommonR
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.getIcon2
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.saver.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import java.text.DateFormat
import java.util.Date
import kotlin.time.Instant

@Composable
internal fun SaverHeader(
    modifier: Modifier = Modifier,
    callerLabel: String?,
    callerPackage: Pkg.Id?,
    createdAt: Instant?,
    workspaceId: Workspace.Id,
    isModal: Boolean = false,
    onBack: () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isModal) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.TwoTone.ArrowBack,
                        contentDescription = stringResource(CommonR.string.general_back_action),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.saver_workspace_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The workspace button (tabs/manager) is meaningless inside a modal export overlay.
            if (!isModal) {
                WorkspaceButton(currentWorkspaceId = workspaceId)
            }
        }

        // "Shared from <app>" context only applies to the ACTION_SEND share flow, not modal export.
        if (!isModal) {
            Spacer(modifier = Modifier.height(12.dp))

            SharedFromCard(
                callerLabel = callerLabel,
                callerPackage = callerPackage,
                createdAt = createdAt,
            )
        }
    }
}

@Composable
private fun SharedFromCard(
    modifier: Modifier = Modifier,
    callerLabel: String?,
    callerPackage: Pkg.Id?,
    createdAt: Instant?,
) {
    val context = LocalContext.current
    val callerName = callerLabel?.takeIf { it != "?" }

    // Resolve + rasterize the source app icon fully off the main thread.
    val appIcon: ImageBitmap? by produceState<ImageBitmap?>(null, callerPackage) {
        value = callerPackage?.let { pkgId ->
            try {
                withContext(Dispatchers.IO) {
                    context.packageManager.getIcon2(pkgId)?.toBitmap()?.asImageBitmap()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    val formattedTime = remember(createdAt) {
        createdAt?.let {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(it.toEpochMilliseconds()))
        }
    }

    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                val icon = appIcon
                if (icon != null) {
                    Image(
                        modifier = Modifier.size(40.dp),
                        bitmap = icon,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Icon(
                        modifier = Modifier.size(28.dp),
                        imageVector = Icons.TwoTone.MoveToInbox,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (callerName != null) {
                        stringResource(R.string.saver_shared_from, callerName)
                    } else {
                        stringResource(R.string.saver_shared_generic)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                formattedTime?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SaverHeaderKnownCallerPreview() {
    PreviewWrapper {
        SaverHeader(
            callerLabel = "Telegram",
            callerPackage = "org.telegram.messenger".toPkgId(),
            createdAt = Instant.fromEpochMilliseconds(1_752_000_000_000),
            workspaceId = Workspace.Id(),
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SaverHeaderUnknownCallerPreview() {
    PreviewWrapper {
        SaverHeader(
            callerLabel = "?",
            callerPackage = null,
            createdAt = Instant.fromEpochMilliseconds(1_752_000_000_000),
            workspaceId = Workspace.Id(),
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SaverHeaderModalPreview() {
    PreviewWrapper {
        SaverHeader(
            callerLabel = null,
            callerPackage = null,
            createdAt = null,
            workspaceId = Workspace.Id(),
            isModal = true,
        )
    }
}
