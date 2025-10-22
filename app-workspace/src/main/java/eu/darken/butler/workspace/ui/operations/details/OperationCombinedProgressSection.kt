package eu.darken.butler.workspace.ui.operations.details

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ExpandLess
import androidx.compose.material.icons.twotone.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
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
    var isExpanded by rememberSaveable { mutableStateOf(true) }

    // Single unified card for all progress
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Clickable section title with expand/collapse
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.operations_details_progress).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Icon(
                    imageVector = if (isExpanded) {
                        Icons.TwoTone.ExpandLess
                    } else {
                        Icons.TwoTone.ExpandMore
                    },
                    contentDescription = if (isExpanded) {
                        "Collapse progress"
                    } else {
                        "Expand progress"
                    },
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Content
            AnimatedVisibility(visible = isExpanded) {
                Column {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Primary Progress
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
