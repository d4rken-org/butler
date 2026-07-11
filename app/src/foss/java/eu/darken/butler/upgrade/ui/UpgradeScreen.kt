package eu.darken.butler.upgrade.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerMascot
import eu.darken.butler.common.compose.ButlerMascotMode
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.ColoredTitleText
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.Preview2Tablet
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import kotlinx.coroutines.launch

@Composable
fun UpgradeScreenHost(vm: UpgradeViewModel = hiltViewModel()) {
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

    UpgradeScreen(
        snackbarHostState = snackbarHostState,
        onNavigateBack = { vm.navUp() },
        onSponsorClick = { vm.openSponsor() },
    )
}

@Composable
fun UpgradeScreen(
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onNavigateBack: () -> Unit,
    onSponsorClick: () -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.upgrade_screen_title)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val isWideScreen = maxWidth >= 600.dp

            if (isWideScreen) {
                WideScreenLayout(
                    onSponsorClick = onSponsorClick,
                )
            } else {
                NarrowScreenLayout(
                    onSponsorClick = onSponsorClick,
                )
            }
        }
    }
}

@Composable
private fun NarrowScreenLayout(
    onSponsorClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ButlerMascot(
            modifier = Modifier
                .padding(bottom = 8.dp)
                .size(96.dp),
            variant = ButlerMascotMode.Animated.MoustacheStroke(),
        )

        ColoredTitleText(
            fullTitle = stringResource(R.string.app_name_upgraded),
            postfix = stringResource(R.string.app_name_upgrade_postfix),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        PreambleCard(modifier = Modifier.padding(bottom = 32.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
            HowToSection(modifier = Modifier.padding(bottom = 32.dp))

            BenefitsList(modifier = Modifier.padding(bottom = 32.dp))

            SponsorButton(
                onSponsorClick = onSponsorClick,
            )
        }
    }
}

@Composable
private fun WideScreenLayout(
    onSponsorClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        // Left column: Mascot, title, benefits
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ButlerMascot(
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .size(120.dp),
                variant = ButlerMascotMode.Animated.MoustacheStroke(),
            )

            ColoredTitleText(
                fullTitle = stringResource(R.string.app_name_upgraded),
                postfix = stringResource(R.string.app_name_upgrade_postfix),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            BenefitsList(modifier = Modifier.fillMaxWidth())
        }

        // Right column: Preamble, how-to, sponsor button
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            PreambleCard()

            HowToSection()

            Spacer(modifier = Modifier.weight(1f))

            SponsorButton(
                onSponsorClick = onSponsorClick,
            )
        }
    }
}

@Composable
private fun PreambleCard(
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Text(
            text = stringResource(R.string.upgrade_screen_preamble),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
private fun HowToSection(
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.upgrade_screen_how_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = stringResource(R.string.upgrade_screen_how_body),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun BenefitsList(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(R.string.upgrade_benefits_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = stringResource(R.string.upgrade_benefit_multitasking),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
        Text(
            text = stringResource(R.string.upgrade_benefit_customization),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
        Text(
            text = stringResource(R.string.upgrade_benefit_extra_options),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
        Text(
            text = stringResource(R.string.upgrade_benefit_early_access),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
        Text(
            text = stringResource(R.string.upgrade_benefit_motivation),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
        Text(
            text = stringResource(R.string.upgrade_benefit_and_more),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun SponsorButton(
    modifier: Modifier = Modifier,
    onSponsorClick: () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Button(
            onClick = onSponsorClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        ) { Text(stringResource(R.string.upgrade_screen_sponsor_action)) }

        Text(
            text = stringResource(R.string.upgrade_screen_sponsor_action_hint),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun UpgradeScreenPhonePreview() {
    UpgradeScreen(
        onNavigateBack = {},
        onSponsorClick = {}
    )
}

@Preview2Tablet
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun UpgradeScreenTabletPreview() {
    UpgradeScreen(
        onNavigateBack = {},
        onSponsorClick = {}
    )
}
