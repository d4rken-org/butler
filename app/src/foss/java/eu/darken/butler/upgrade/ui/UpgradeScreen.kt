package eu.darken.butler.upgrade.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerMascot
import eu.darken.butler.common.compose.ButlerMascotMode
import eu.darken.butler.common.compose.ColoredTitleText
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import kotlinx.coroutines.launch

@Composable
fun UpgradeScreenHost(
    manage: Boolean,
    vm: UpgradeViewModel = hiltViewModel(
        key = "upgrade-manage-$manage",
        creationCallback = { factory: UpgradeViewModel.Factory -> factory.create(manage = manage) },
    ),
) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.snackbarEvent.collect { stringResId ->
            scope.launch { snackbarHostState.showSnackbar(context.getString(stringResId)) }
        }
    }

    LifecycleResumeEffect(Unit) {
        vm.onAppResumed()
        onPauseOrDispose {}
    }

    val view by vm.state.collectAsState(initial = null)

    UpgradeScreen(
        view = view,
        snackbarHostState = snackbarHostState,
        onNavigateBack = { vm.navUp() },
        onSponsorClick = { vm.openSponsor() },
        onRecurringSponsorClick = { vm.openRecurringSponsor() },
        onShowUpgradeOptions = { vm.onShowUpgradeOptions() },
    )
}

@Composable
fun UpgradeScreen(
    view: FossUpgradeView?,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onSponsorClick: () -> Unit,
    onRecurringSponsorClick: () -> Unit,
    onShowUpgradeOptions: () -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(text = stringResource(R.string.upgrade_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(eu.darken.butler.common.R.string.general_back_action),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (view) {
                null -> Unit
                FossUpgradeView.PITCH -> PitchContent(onSponsorClick = onSponsorClick)
                FossUpgradeView.STATUS_FREE -> StatusFreeContent(onShowUpgradeOptions = onShowUpgradeOptions)
                FossUpgradeView.STATUS_UPGRADED -> StatusUpgradedContent(onRecurringSponsorClick = onRecurringSponsorClick)
            }
        }
    }
}

@Composable
private fun PitchContent(onSponsorClick: () -> Unit) {
    ButlerMascot(
        modifier = Modifier.size(96.dp),
        variant = ButlerMascotMode.Animated.MoustacheStroke(),
    )
    ColoredTitleText(
        fullTitle = stringResource(R.string.app_name_upgraded),
        postfix = stringResource(R.string.app_name_upgrade_postfix),
        style = MaterialTheme.typography.headlineMedium,
    )
    PreambleCard()
    HowToSection()
    BenefitsList()
    Column(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = onSponsorClick,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.upgrade_screen_sponsor_action)) }
        Text(
            text = stringResource(R.string.upgrade_screen_sponsor_action_hint),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
    }
}

@Composable
private fun StatusFreeContent(onShowUpgradeOptions: () -> Unit) {
    ButlerMascot(
        modifier = Modifier.size(96.dp),
        variant = ButlerMascotMode.Static.Sad(),
    )
    Text(
        text = stringResource(R.string.upgrade_screen_status_free_title),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(
        text = stringResource(R.string.upgrade_screen_status_free_body),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Button(
        onClick = onShowUpgradeOptions,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.upgrade_screen_status_free_action)) }
}

@Composable
private fun StatusUpgradedContent(onRecurringSponsorClick: () -> Unit) {
    ButlerMascot(
        modifier = Modifier.size(96.dp),
        variant = ButlerMascotMode.Static.Happy(),
    )
    ColoredTitleText(
        fullTitle = stringResource(R.string.app_name_upgraded),
        postfix = stringResource(R.string.app_name_upgrade_postfix),
        style = MaterialTheme.typography.headlineMedium,
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
        ),
    ) {
        Text(
            text = stringResource(R.string.upgrade_screen_status_upgraded_body),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.upgrade_screen_recurring_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.upgrade_screen_recurring_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = onRecurringSponsorClick,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.upgrade_screen_recurring_action)) }
        }
    }
}

@Composable
private fun PreambleCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Text(
            text = stringResource(R.string.upgrade_screen_preamble),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun HowToSection() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.upgrade_screen_how_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(
            text = stringResource(R.string.upgrade_screen_how_body),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun BenefitsList() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.upgrade_benefits_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        listOf(
            R.string.upgrade_benefit_multitasking,
            R.string.upgrade_benefit_customization,
            R.string.upgrade_benefit_extra_options,
            R.string.upgrade_benefit_early_access,
            R.string.upgrade_benefit_motivation,
            R.string.upgrade_benefit_and_more,
        ).forEach {
            Text(
                text = stringResource(it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
