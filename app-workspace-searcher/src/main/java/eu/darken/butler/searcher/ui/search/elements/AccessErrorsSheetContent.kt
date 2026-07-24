package eu.darken.butler.searcher.ui.search.elements

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Error
import androidx.compose.material.icons.twotone.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.searcher.R
import eu.darken.butler.searcher.core.engine.SearchEngine
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.workspace.contracts.searcher.SearchTarget

/**
 * Detail sheet behind the progress card's "N items couldn't be accessed" line: lists the
 * inaccessible paths and, when a viable mechanism exists (root/Shizuku available — already
 * availability-filtered by PathPermissionCheck), offers the setup action. When nothing can unlock
 * the items, the body text is the terminal explanation and no action is shown.
 */
@Composable
fun AccessErrorsSheetContent(
    targetProgress: List<SearchEngine.SearchTargetProgress>,
    accessErrorRequirements: PathRequirements,
    onUnlockAccess: () -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    val erroredTargets = targetProgress.filter { it.accessErrorCount > 0 }
    val totalErrors = erroredTargets.sumOf { it.accessErrorCount }
    val showLocationLabels = erroredTargets.size > 1

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp + bottomPadding),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.TwoTone.Error,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = pluralStringResource(
                        R.plurals.searcher_progress_items_inaccessible_count,
                        totalErrors,
                        totalErrors,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Text(
                text = stringResource(
                    if (accessErrorRequirements.needsSetup) R.string.searcher_access_sheet_body_unlockable
                    else R.string.searcher_access_sheet_body_protected
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            erroredTargets.forEach { progress ->
                if (showLocationLabels) {
                    Text(
                        text = progress.target.displayText.get(context),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                } else {
                    Spacer(modifier = Modifier.padding(top = 8.dp))
                }
                progress.relativeErrorLabels(context).forEach { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                val more = progress.accessErrorCount - progress.accessErrorPaths.size
                if (more > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.searcher_progress_inaccessible_more, more, more),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            if (accessErrorRequirements.needsSetup) {
                Button(
                    onClick = onUnlockAccess,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.searcher_access_sheet_unlock_action))
                }
            }
        }
    }
}

// Inaccessible paths rendered relative to the target root so the distinguishing tail stays visible
// (an end-ellipsized absolute path would show only the shared prefix). Falls back to the full path.
private fun SearchEngine.SearchTargetProgress.relativeErrorLabels(context: Context): List<String> {
    val rootSegments = (target as? SearchTarget.Path)?.path?.segments
    return accessErrorPaths.map { errorPath ->
        val segs = errorPath.segments
        if (rootSegments != null && segs.size > rootSegments.size && segs.take(rootSegments.size) == rootSegments) {
            segs.drop(rootSegments.size).joinToString("/")
        } else {
            errorPath.userReadablePath.get(context)
        }
    }
}

private fun previewProgress(
    path: String,
    errorPaths: List<String>,
    accessErrorCount: Int = errorPaths.size,
) = SearchEngine.SearchTargetProgress(
    target = SearchTarget.Path.from(LocalPath.build(path)),
    itemsScanned = 143,
    resultsFound = 0,
    status = SearchEngine.SearchTargetProgress.Status.COMPLETED,
    accessErrorCount = accessErrorCount,
    accessErrorPaths = errorPaths.map { LocalPath.build(it) },
)

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AccessErrorsSheetContentUnlockablePreview() {
    AccessErrorsSheetContent(
        targetProgress = listOf(
            previewProgress(
                path = "/storage/emulated/0",
                errorPaths = listOf(
                    "/storage/emulated/0/Android/data",
                    "/storage/emulated/0/Android/obb",
                ),
            ),
        ),
        accessErrorRequirements = PathRequirements(
            combos = setOf(setOf(SetupModule.Type.ROOT), setOf(SetupModule.Type.SHIZUKU)),
        ),
        onUnlockAccess = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AccessErrorsSheetContentProtectedPreview() {
    AccessErrorsSheetContent(
        targetProgress = listOf(
            previewProgress(
                path = "/storage/emulated/0",
                errorPaths = listOf(
                    "/storage/emulated/0/Android/data",
                    "/storage/emulated/0/Android/obb",
                ),
            ),
        ),
        accessErrorRequirements = PathRequirements(),
        onUnlockAccess = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AccessErrorsSheetContentMultiLocationPreview() {
    AccessErrorsSheetContent(
        targetProgress = listOf(
            previewProgress(
                path = "/storage/emulated/0",
                errorPaths = listOf(
                    "/storage/emulated/0/Android/data",
                    "/storage/emulated/0/Android/obb",
                ),
                accessErrorCount = 5,
            ),
            previewProgress(
                path = "/storage/ABCD-1234",
                errorPaths = listOf("/storage/ABCD-1234/Android/data"),
            ),
        ),
        accessErrorRequirements = PathRequirements(
            combos = setOf(setOf(SetupModule.Type.ROOT)),
        ),
        onUnlockAccess = {},
    )
}
