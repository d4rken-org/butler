package eu.darken.butler.apps.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.GetApp
import androidx.compose.material.icons.twotone.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.apps.R
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

@Composable
fun AppDetailsActionBar(
    modifier: Modifier = Modifier,
    onExportApk: () -> Unit,
    onMoreOptions: () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onExportApk,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.TwoTone.GetApp,
                    contentDescription = stringResource(R.string.apps_action_export_apk),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            IconButton(
                onClick = onMoreOptions,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.TwoTone.MoreVert,
                    contentDescription = stringResource(R.string.apps_details_more_options),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Preview2
@Composable
private fun AppDetailsActionBarPreview() {
    PreviewWrapper {
        AppDetailsActionBar(
            onExportApk = {},
            onMoreOptions = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}
