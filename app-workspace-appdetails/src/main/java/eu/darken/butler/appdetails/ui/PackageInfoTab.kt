package eu.darken.butler.appdetails.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.appdetails.R
import eu.darken.butler.appdetails.core.AppDetailsWorkspace
import eu.darken.butler.appdetails.core.AppDetailsWorkspaceViewModel

@Composable
fun PackageInfoTab(
    state: AppDetailsWorkspace.State,
    vm: AppDetailsWorkspaceViewModel,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.appdetails_packageinfo_comingsoon_msg),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
