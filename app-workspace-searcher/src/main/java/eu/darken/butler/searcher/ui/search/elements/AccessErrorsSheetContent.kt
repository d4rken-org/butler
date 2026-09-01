package eu.darken.butler.searcher.ui.search.elements

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
 * inaccessible paths in full (absolute — a file explorer must be exact about locations) and, when
 * a mechanism exists that could unlock them, offers the setup action. For protected local paths
 * that means root (offered without checking for a known root manager package) or Shizuku (only
 * when its app is installed). When nothing can unlock the items, the body text is the terminal
 * explanation and no action is shown.
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
    // Full absolute paths, deduped across targets (overlapping roots can report the same path).
    // The "more" line only counts entries hidden by the per-target retention cap.
    val fullPaths = erroredTargets
        .flatMap { it.accessErrorPaths }
        .distinct()
        .map { it.userReadablePath.get(context) }
    val truncatedCount = erroredTargets.sumOf { it.accessErrorCount - it.accessErrorPaths.size }

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

            Spacer(modifier = Modifier.padding(top = 8.dp))
            fullPaths.forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (truncatedCount > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.searcher_progress_inaccessible_more,
                        truncatedCount,
                        truncatedCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
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
