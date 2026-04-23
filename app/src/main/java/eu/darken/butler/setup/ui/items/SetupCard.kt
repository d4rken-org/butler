package eu.darken.butler.setup.ui.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.setup.core.SetupAction
import eu.darken.butler.setup.core.SetupItem
import eu.darken.butler.setup.core.SetupModule
import kotlin.time.Clock

@Composable
fun SetupCard(
    modifier: Modifier = Modifier,
    item: SetupItem,
    onExecuteAction: (SetupAction) -> Unit,
    onOpenHelp: () -> Unit,
) {
    val isComplete = (item.state as? SetupModule.State.Current)?.isComplete == true

    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isComplete) 2.dp else 4.dp
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isComplete) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Top row: Icon + Title on left, Help button on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = getSetupIcon(item.type),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        text = stringResource(item.type.labelRes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                IconButton(
                    onClick = onOpenHelp,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Help,
                        contentDescription = stringResource(R.string.setup_help_description),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            // Description text taking full width
            Text(
                text = getSetupDescription(item.type),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Actions (status + button/switch)
            SetupActions(
                item = item,
                onExecuteAction = onExecuteAction
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SetupCardCompletePreview() {
    SetupCard(
        modifier = Modifier.width(360.dp),
        item = SetupItem(
            type = SetupModule.Type.STORAGE,
            state = object : SetupModule.State.Current {
                override val type = SetupModule.Type.STORAGE
                override val isComplete = true
            },
            isRequired = false,
            priority = 1,
        ),
        onExecuteAction = {},
        onOpenHelp = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SetupCardRequiredPreview() {
    SetupCard(
        modifier = Modifier.width(360.dp),
        item = SetupItem(
            type = SetupModule.Type.STORAGE,
            state = object : SetupModule.State.Current {
                override val type = SetupModule.Type.STORAGE
                override val isComplete = false
            },
            isRequired = true,
            priority = 1,
        ),
        onExecuteAction = {},
        onOpenHelp = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SetupCardOptionalPreview() {
    SetupCard(
        modifier = Modifier.width(360.dp),
        item = SetupItem(
            type = SetupModule.Type.NOTIFICATION,
            state = object : SetupModule.State.Current {
                override val type = SetupModule.Type.NOTIFICATION
                override val isComplete = false
            },
            isRequired = false,
            priority = 2,
        ),
        onExecuteAction = {},
        onOpenHelp = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SetupCardLoadingPreview() {
    SetupCard(
        modifier = Modifier.width(360.dp),
        item = SetupItem(
            type = SetupModule.Type.ROOT,
            state = object : SetupModule.State.Loading {
                override val type = SetupModule.Type.ROOT
                override val startAt = Clock.System.now()
            },
            isRequired = false,
            priority = 4,
        ),
        onExecuteAction = {},
        onOpenHelp = {},
    )
}