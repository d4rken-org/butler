package eu.darken.butler.workspace.ui.operations.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.operations.Operation

@Composable
internal fun OperationAffectedFilesSection(
    affectedPaths: Collection<Operation.Report.PathChange>,
) {
    val affectedPathList = remember(affectedPaths) { affectedPaths.toList() }

    OperationSection(
        title = stringResource(
            R.string.operations_details_affected_paths_with_count,
            affectedPaths.size,
        ),
        initiallyExpanded = false,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp),
        ) {
            items(
                items = affectedPathList,
                key = { "${it.path.path}:${it.change}" },
            ) { pathChange ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = when (pathChange.change) {
                            Operation.Report.PathChange.Change.ADDED -> "+"
                            Operation.Report.PathChange.Change.REMOVED -> "\u2212"
                            Operation.Report.PathChange.Change.MODIFIED -> "~"
                            Operation.Report.PathChange.Change.TRASHED -> "\u267B"
                            Operation.Report.PathChange.Change.MOVED -> "\u2192"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = when (pathChange.change) {
                            Operation.Report.PathChange.Change.ADDED -> MaterialTheme.colorScheme.primary
                            Operation.Report.PathChange.Change.REMOVED -> MaterialTheme.colorScheme.error
                            Operation.Report.PathChange.Change.MODIFIED -> MaterialTheme.colorScheme.secondary
                            Operation.Report.PathChange.Change.TRASHED -> MaterialTheme.colorScheme.tertiary
                            Operation.Report.PathChange.Change.MOVED -> MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.width(16.dp)
                    )

                    Text(
                        text = pathChange.path.userReadablePath.asComposable(),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
