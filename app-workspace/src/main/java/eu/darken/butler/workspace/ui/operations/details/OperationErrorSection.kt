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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ExpandLess
import androidx.compose.material.icons.twotone.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.ui.operations.OperationDisplay

@Composable
internal fun OperationErrorSection(
    state: OperationDisplay.State.Failed,
) {
    var isExpanded by remember { mutableStateOf(true) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp)
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
                    text = stringResource(R.string.operations_details_error).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                )

                Icon(
                    imageVector = if (isExpanded) {
                        Icons.TwoTone.ExpandLess
                    } else {
                        Icons.TwoTone.ExpandMore
                    },
                    contentDescription = if (isExpanded) {
                        "Collapse error"
                    } else {
                        "Expand error"
                    },
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                )
            }

            // Content
            AnimatedVisibility(visible = isExpanded) {
                Column {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = state.summary.asComposable(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}
