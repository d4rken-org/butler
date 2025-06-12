package eu.darken.butler.workspace.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.R
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.SampleContent
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.main.ui.AppNav

@Composable
fun WorkspaceScreenHost(vm: WorkspaceViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)

    val state by waitForState(vm.state)

    state?.let { state ->
        WorkspaceScreen(
            state = state,
            onNavigateUp = { vm.goTo(null) },
            onNavToSettings = { vm.goTo(AppNav.Settings) }
        )
    }
}

@Composable
fun WorkspaceScreen(
    state: WorkspaceViewModel.State,
    onNavigateUp: () -> Unit,
    onNavToSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.mascot),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(eu.darken.butler.common.R.string.app_name))
                    }
                },
                actions = {
                    IconButton(onClick = onNavToSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings_label)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.Top
        ) {
            item { SampleContent {} }
            item { SampleContent {} }
        }
    }
}

@Preview2
@Composable
private fun WorkspaceScreenPreview() {
    PreviewWrapper {
        WorkspaceScreen(state = WorkspaceViewModel.State(), onNavigateUp = {}, onNavToSettings = {})
    }
}
