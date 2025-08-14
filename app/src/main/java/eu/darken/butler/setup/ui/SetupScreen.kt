package eu.darken.butler.setup.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import eu.darken.butler.R
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.setup.core.SetupAction
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.setup.ui.items.SetupCard

@Composable
fun SetupScreen(
    state: SetupViewModel.State,
    onNavigateUp: () -> Unit,
    onExecuteAction: (SetupModule.Type, SetupAction) -> Unit,
    onOpenHelp: (SetupModule.Type) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setup_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(
                                eu.darken.butler.common.R.string.general_back_action
                            )
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
            }

            items(state.items) { item ->
                SetupCard(
                    item = item,
                    onExecuteAction = { action -> onExecuteAction(item.type, action) },
                    onOpenHelp = { onOpenHelp(item.type) }
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Preview2
@Composable
private fun SetupScreenPreview() {
    // Preview moved to items package
}

@Composable
fun SetupScreenHost(
    options: SetupScreenOptions = SetupScreenOptions(),
    vm: SetupViewModel = hiltViewModel(
        creationCallback = { factory: SetupViewModel.Factory ->
            factory.create(options = options)
        }
    )
) {
    ErrorEventHandler(vm)

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isPermissionSettingsLaunched by remember { mutableStateOf(false) }
    var isRuntimePermissionLaunched by remember { mutableStateOf(false) }

    // Runtime permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        log(TAG) { "Runtime permissions result: $permissions" }
        isRuntimePermissionLaunched = false
        vm.refresh()
    }

    // Handle permission request intents
    LaunchedEffect(vm) {
        vm.permissionRequestEvents.collect { intent ->
            isPermissionSettingsLaunched = true
            context.startActivity(intent)
        }
    }

    // Handle runtime permission requests
    LaunchedEffect(vm) {
        vm.runtimePermissionEvents.collect { permissions ->
            isRuntimePermissionLaunched = true
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    // Monitor lifecycle to refresh when returning from settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && (isPermissionSettingsLaunched || isRuntimePermissionLaunched)) {
                // Refresh when returning from permission settings
                vm.refresh()
                isPermissionSettingsLaunched = false
                isRuntimePermissionLaunched = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val state by waitForState(vm.state)

    state?.let { vmState ->
        SetupScreen(
            state = vmState,
            onNavigateUp = { vm.navUp() },
            onExecuteAction = { type, action -> vm.executeAction(type, action) },
            onOpenHelp = { type -> vm.openHelp(type) }
        )
    }
}

private val TAG = logTag("Setup", "Screen")