package eu.darken.butler.workspace.ui.operations.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.workspace.R

@Composable
internal fun OperationCombinedProgressSection(
    primaryProgress: Progress.Data,
    secondaryProgress: Progress.Data?,
) {
    OperationSection(title = stringResource(R.string.operations_details_progress)) {
        OperationProgressDisplay(
            progressData = primaryProgress,
            isPrimary = true,
            modifier = Modifier.fillMaxWidth()
        )

        // Divider and Secondary Progress (if exists)
        secondaryProgress?.let { secondary ->
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            OperationProgressDisplay(
                progressData = secondary,
                isPrimary = false,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun OperationProgressDisplay(
    progressData: Progress.Data,
    isPrimary: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(if (isPrimary) 8.dp else 6.dp)
    ) {
        when (val count = progressData.count) {
            is Progress.Count.Percent,
            is Progress.Count.Counter,
            is Progress.Count.Size -> {
                // Header with title and percentage
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = progressData.primary.asComposable(),
                        style = if (isPrimary) {
                            MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        color = if (isPrimary) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        },
                    )
                    Text(
                        text = count.displayValue.asComposable(),
                        style = if (isPrimary) {
                            MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                        } else {
                            MaterialTheme.typography.bodySmall
                        },
                        color = if (isPrimary) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        },
                    )
                }

                // Progress bar with enhanced styling
                LinearProgressIndicator(
                    progress = { count.percentage },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isPrimary) {
                                Modifier.height(8.dp)
                            } else {
                                Modifier.height(3.dp)
                            }
                        ),
                    color = if (isPrimary) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    },
                    trackColor = if (isPrimary) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    },
                )

                // Secondary text
                if (progressData.secondary != CaString.EMPTY) {
                    Text(
                        text = progressData.secondary.asComposable(),
                        style = if (isPrimary) {
                            MaterialTheme.typography.bodyMedium
                        } else {
                            MaterialTheme.typography.bodySmall
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (isPrimary) 0.7f else 0.6f
                        )
                    )
                }
            }
            is Progress.Count.Indeterminate -> {
                Text(
                    text = progressData.primary.asComposable(),
                    style = if (isPrimary) {
                        MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = if (isPrimary) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    },
                )
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isPrimary) {
                                Modifier.height(8.dp)
                            } else {
                                Modifier.height(3.dp)
                            }
                        ),
                    color = if (isPrimary) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    },
                    trackColor = if (isPrimary) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    },
                )
            }
            is Progress.Count.None -> {
                Text(
                    text = progressData.primary.asComposable(),
                    style = if (isPrimary) {
                        MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = if (isPrimary) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    },
                )
            }
        }
    }
}
