package eu.darken.butler.explorer.ui.explorer.issues

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Cancel
import androidx.compose.material.icons.twotone.Error
import androidx.compose.material.icons.twotone.ExpandLess
import androidx.compose.material.icons.twotone.ExpandMore
import androidx.compose.material.icons.twotone.Refresh
import androidx.compose.material.icons.twotone.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.operations.Issue
import eu.darken.butler.explorer.R
import java.io.IOException
import kotlin.time.Instant

@Composable
fun UnknownErrorIssueSheet(
    issue: Issue.UnknownError,
    onResolution: (Issue.UnknownError.Resolution) -> Unit,
    modifier: Modifier = Modifier,
) {
    var applyToAll by remember { mutableStateOf(false) }
    var showTechnicalDetails by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Title with error icon
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.TwoTone.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(R.string.explorer_issue_unknown_error_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        HorizontalDivider()

        Text(
            modifier  = modifier.padding(bottom = 8.dp),
            text = issue.errorMessage.asComposable(),
            style = MaterialTheme.typography.bodyMedium,
        )

        // Show source file if available
        issue.source?.let { source ->
            Text(
                text = stringResource(R.string.explorer_issue_common_source_file),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PathIssueFileComparisonCard(lookup = source)
        }

        // Show destination file if available
        issue.destination?.let { destination ->
            Text(
                text = stringResource(R.string.explorer_issue_common_destination_file),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PathIssueFileComparisonCard(lookup = destination)
        }

        // Technical details section
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        modifier = Modifier.padding(16.dp),
                        text = stringResource(
                            if (showTechnicalDetails) {
                                R.string.explorer_issue_unknown_error_hide_details
                            } else {
                                R.string.explorer_issue_unknown_error_show_details
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(
                        onClick = { showTechnicalDetails = !showTechnicalDetails },
                    ) {
                        Icon(
                            imageVector = if (showTechnicalDetails) {
                                Icons.TwoTone.ExpandLess
                            } else {
                                Icons.TwoTone.ExpandMore
                            },
                            contentDescription = null,
                        )
                    }
                }

                AnimatedVisibility(visible = showTechnicalDetails) {
                    Column {
                        HorizontalDivider()
                        SelectionContainer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp)
                        ) {
                            Text(
                                text = issue.exception.stackTraceToString(),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
            }
        }

        // Apply to all checkbox (if skipping is allowed)
        if (issue.canSkip) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = applyToAll,
                    onCheckedChange = { applyToAll = it },
                )
                Text(
                    text = stringResource(R.string.explorer_issue_apply_all),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // Action buttons
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (issue.canRetry) {
                    Button(
                        onClick = {
                            onResolution(Issue.UnknownError.Resolution.Retry(applyToAll))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.TwoTone.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(stringResource(eu.darken.butler.common.R.string.general_retry_action))
                        }
                    }
                }

                if (issue.canSkip) {
                    OutlinedButton(
                        onClick = {
                            onResolution(Issue.UnknownError.Resolution.Skip(applyToAll))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.TwoTone.SkipNext,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(stringResource(R.string.explorer_issue_common_skip))
                        }
                    }
                }
            }

            TextButton(
                onClick = {
                    onResolution(Issue.UnknownError.Resolution.Cancel())
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.Cancel,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(stringResource(R.string.explorer_issue_common_cancel))
                }
            }
        }
    }
}

@Preview2
@Composable
private fun UnknownErrorIssueSheetIOErrorPreview() {
    PreviewWrapper {
        UnknownErrorIssueSheet(
            issue = Issue.UnknownError(
                source = LocalPathLookup(
                    lookedUp = LocalPath.build("/storage/emulated/0/Documents/corrupted_file.pdf"),
                    fileType = FileType.FILE,
                    size = 1024 * 512, // 512KB
                    modifiedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() - 3600000), // 1 hour ago
                    target = null,
                ),
                destination = LocalPathLookup(
                    lookedUp = LocalPath.build("/storage/emulated/0/Backup/corrupted_file.pdf"),
                    fileType = FileType.FILE,
                    size = 0,
                    modifiedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                    target = null,
                ),
                exception = IOException("Input/output error"),
                errorMessage = "java.io.IOException: Input/output error".toCaString(),
                canSkip = true,
                canRetry = true,
            ),
            onResolution = {},
        )
    }
}

@Preview2
@Composable
private fun UnknownErrorIssueSheetSecurityErrorPreview() {
    PreviewWrapper {
        UnknownErrorIssueSheet(
            issue = Issue.UnknownError(
                source = LocalPathLookup(
                    lookedUp = LocalPath.build("/data/data/com.example.app/files/sensitive.dat"),
                    fileType = FileType.FILE,
                    size = 1024 * 256, // 256KB
                    modifiedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() - 86400000), // 1 day ago
                    target = null,
                ),
                exception = SecurityException("Permission denied for this operation"),
                errorMessage = "java.lang.SecurityException: Permission denied for this operation".toCaString(),
                canSkip = true,
                canRetry = false,
            ),
            onResolution = {},
        )
    }
}

@Preview2
@Composable
private fun UnknownErrorIssueSheetUnknownErrorPreview() {
    PreviewWrapper {
        UnknownErrorIssueSheet(
            issue = Issue.UnknownError(
                errorMessage = "java.lang.RuntimeException: Unexpected vendor-specific error occurred".toCaString(),
                exception = RuntimeException("Unexpected vendor-specific error occurred"),
                canSkip = true,
                canRetry = true,
            ),
            onResolution = {},
        )
    }
}