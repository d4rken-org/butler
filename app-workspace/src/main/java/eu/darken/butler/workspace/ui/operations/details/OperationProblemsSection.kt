package eu.darken.butler.workspace.ui.operations.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.operations.Operation

/**
 * The sub-items an operation could not process. [totalCount] is what the report counted, which is
 * larger than [problems] when the report capped its list.
 */
@Composable
internal fun OperationProblemsSection(
    problems: Collection<Operation.Report.Problem>,
    totalCount: Int,
) {
    val problemList = remember(problems) { problems.toList() }

    OperationSection(
        title = stringResource(R.string.operations_details_problems_with_count, totalCount),
        initiallyExpanded = false,
        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
        accentColor = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
        dividerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(items = problemList) { problem ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = problem.path.userReadablePath.asComposable(),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )

                    problem.message?.takeIf { it.isNotBlank() }?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (totalCount > problemList.size) {
                item {
                    Text(
                        modifier = Modifier.padding(top = 4.dp),
                        text = stringResource(
                            R.string.operations_details_problems_more,
                            totalCount - problemList.size,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private val previewProblems = listOf(
    Operation.Report.Problem(
        path = LocalPath.build("/storage", "emulated", "0", "Android", "data", "com.example.app"),
        message = "Permission denied",
    ),
    Operation.Report.Problem(
        path = LocalPath.build("/storage", "emulated", "0", "Download", "broken.link"),
        message = "No such file or directory",
    ),
    Operation.Report.Problem(
        path = LocalPath.build("/storage", "emulated", "0", "Movies", "clip.mkv"),
        message = null,
    ),
)

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OperationProblemsSectionPreview() {
    Box(modifier = Modifier.width(360.dp)) {
        OperationProblemsSection(
            problems = previewProblems,
            totalCount = previewProblems.size,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OperationProblemsSectionCappedPreview() {
    Box(modifier = Modifier.width(360.dp)) {
        OperationProblemsSection(
            problems = previewProblems,
            totalCount = 512,
        )
    }
}
