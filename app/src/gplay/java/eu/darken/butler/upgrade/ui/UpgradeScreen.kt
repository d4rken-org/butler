package eu.darken.butler.upgrade.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerMascot
import eu.darken.butler.common.compose.ButlerMascotMode
import eu.darken.butler.common.compose.ColoredTitleText
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.Preview2Tablet
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.ui.waitForState


@Composable
fun UpgradeScreenHost(vm: UpgradeViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var showRestoreFailedDialog by remember { mutableStateOf(false) }

    ErrorEventHandler(vm)

    val state by waitForState(vm.state)
    log(vm.tag) { "Screen state: $state" }
    log(vm.tag) { "showRestoreFailedDialog: $showRestoreFailedDialog" }

    state?.let { state ->
        UpgradeScreen(
            state = state,
            onNavigateBack = { vm.navUp() },
            onGoIap = { vm.onGoIap(context as android.app.Activity) },
            onGoSubscription = { vm.onGoSubscription(context as android.app.Activity) },
            onGoSubscriptionTrial = { vm.onGoSubscriptionTrial(context as android.app.Activity) },
            onRestorePurchase = { vm.restorePurchase() },
        )
    }

    LaunchedEffect(Unit) {
        vm.events.collect { event: UpgradeEvents ->
            when (event) {
                UpgradeEvents.RestoreFailed -> {
                    log(vm.tag) { "Received RestoreFailed event. Setting showRestoreFailedDialog to true." }
                    showRestoreFailedDialog = true
                }
            }
        }
    }

    if (showRestoreFailedDialog) {
        RestoreFailedDialog(
            onDismiss = { showRestoreFailedDialog = false }
        )
    }
}

@Composable
fun UpgradeScreen(
    state: UpgradeViewModel.State,
    onNavigateBack: () -> Unit,
    onGoIap: () -> Unit,
    onGoSubscription: () -> Unit,
    onGoSubscriptionTrial: () -> Unit,
    onRestorePurchase: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val scrollProgress by remember {
        derivedStateOf { (scrollState.value / 200f).coerceIn(0f, 1f) }
    }

    val toolbarAlpha by animateFloatAsState(
        targetValue = scrollProgress,
        animationSpec = tween(300),
        label = "toolbarAlpha"
    )

    val contentAlpha by animateFloatAsState(
        targetValue = 1f - scrollProgress,
        animationSpec = tween(300),
        label = "contentAlpha"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    AnimatedVisibility(
                        visible = scrollProgress > 0.5f
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ButlerMascot(
                                modifier = Modifier
                                    .graphicsLayer(alpha = toolbarAlpha)
                                    .size(32.dp),
                                variant = ButlerMascotMode.Static.Happy,
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            ColoredTitleText(
                                fullTitle = stringResource(R.string.app_name_upgraded),
                                postfix = stringResource(R.string.app_name_upgrade_postfix),
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.graphicsLayer(alpha = toolbarAlpha)
                            )
                        }
                    }
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
                    state = state,
                    scrollState = scrollState,
                    contentAlpha = contentAlpha,
                    onGoIap = onGoIap,
                    onGoSubscription = onGoSubscription,
                    onGoSubscriptionTrial = onGoSubscriptionTrial,
                    onRestorePurchase = onRestorePurchase,
                )
            } else {
                NarrowScreenLayout(
                    state = state,
                    scrollState = scrollState,
                    contentAlpha = contentAlpha,
                    onGoIap = onGoIap,
                    onGoSubscription = onGoSubscription,
                    onGoSubscriptionTrial = onGoSubscriptionTrial,
                    onRestorePurchase = onRestorePurchase,
                )
            }
        }
    }
}

@Composable
private fun NarrowScreenLayout(
    state: UpgradeViewModel.State,
    scrollState: androidx.compose.foundation.ScrollState,
    contentAlpha: Float,
    onGoIap: () -> Unit,
    onGoSubscription: () -> Unit,
    onGoSubscriptionTrial: () -> Unit,
    onRestorePurchase: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 32.dp)
            .padding(top = 8.dp, bottom = 32.dp)
            .animateContentSize(animationSpec = tween(300))
    ) {
        MascotHeader(contentAlpha = contentAlpha)

        Spacer(modifier = Modifier.height(8.dp))

        PreambleCard()

        BenefitsList(modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        HowToSection()

        SubscriptionButtons(
            state = state,
            onGoIap = onGoIap,
            onGoSubscription = onGoSubscription,
            onGoSubscriptionTrial = onGoSubscriptionTrial,
        )

        RestorePurchaseSection(onRestorePurchase = onRestorePurchase)
    }
}

@Composable
private fun WideScreenLayout(
    state: UpgradeViewModel.State,
    scrollState: androidx.compose.foundation.ScrollState,
    contentAlpha: Float,
    onGoIap: () -> Unit,
    onGoSubscription: () -> Unit,
    onGoSubscriptionTrial: () -> Unit,
    onRestorePurchase: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(32.dp)
            .animateContentSize(animationSpec = tween(300)),
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
                    .size(120.dp)
                    .graphicsLayer(alpha = contentAlpha),
                variant = ButlerMascotMode.Animated.Drink(),
            )

            ColoredTitleText(
                fullTitle = stringResource(R.string.app_name_upgraded),
                postfix = stringResource(R.string.app_name_upgrade_postfix),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier
                    .padding(bottom = 24.dp)
                    .graphicsLayer(alpha = contentAlpha)
            )

            BenefitsList(modifier = Modifier.fillMaxWidth())
        }

        // Right column: Preamble, how-to, buttons
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PreambleCard()

            HowToSection()

            Spacer(modifier = Modifier.weight(1f))

            SubscriptionButtons(
                state = state,
                onGoIap = onGoIap,
                onGoSubscription = onGoSubscription,
                onGoSubscriptionTrial = onGoSubscriptionTrial,
            )

            RestorePurchaseSection(onRestorePurchase = onRestorePurchase)
        }
    }
}

@Composable
private fun MascotHeader(
    contentAlpha: Float,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer(alpha = contentAlpha),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ButlerMascot(
            modifier = Modifier.size(88.dp),
            variant = ButlerMascotMode.Animated.Drink(),
        )
        Spacer(modifier = Modifier.size(8.dp))
        ColoredTitleText(
            fullTitle = stringResource(R.string.app_name_upgraded),
            postfix = stringResource(R.string.app_name_upgrade_postfix),
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}

@Composable
private fun PreambleCard(
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Text(
            text = stringResource(R.string.upgrade_screen_preamble),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
            textAlign = TextAlign.Start,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
private fun BenefitsList(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
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
private fun SubscriptionButtons(
    state: UpgradeViewModel.State,
    onGoIap: () -> Unit,
    onGoSubscription: () -> Unit,
    onGoSubscriptionTrial: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Subscription Button
        AnimatedVisibility(
            visible = state.subState.available || state.trialState.available || state.isLoadingPrices,
            enter = fadeIn(animationSpec = tween(600, delayMillis = 400))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                if (state.isLoadingPrices) {
                    OutlinedButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                } else if (state.trialState.available) {
                    Button(
                        onClick = onGoSubscriptionTrial,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.upgrade_screen_subscription_trial_action),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = onGoSubscription,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(text = stringResource(R.string.upgrade_screen_subscription_action))
                    }
                }

                Text(
                    text = if (state.isLoadingPrices) {
                        "…"
                    } else {
                        stringResource(
                            R.string.upgrade_screen_subscription_action_hint,
                            state.subState.formattedPrice ?: ""
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // One-time Purchase Button
        AnimatedVisibility(
            visible = state.iapState.available || state.isLoadingPrices,
            enter = fadeIn(animationSpec = tween(600, delayMillis = 500))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onGoIap,
                    enabled = !state.isLoadingPrices,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    if (state.isLoadingPrices) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(text = stringResource(R.string.upgrade_screen_iap_action))
                    }
                }

                Text(
                    text = if (state.isLoadingPrices) {
                        "…"
                    } else {
                        stringResource(
                            R.string.upgrade_screen_iap_action_hint,
                            state.iapState.formattedPrice ?: ""
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Show message when no upgrade options are available
        if (!state.isLoadingPrices && !state.iapState.available && !state.subState.available && !state.trialState.available) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                )
            ) {
                Text(
                    text = stringResource(R.string.upgrades_gplay_unavailable_error_description),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun RestorePurchaseSection(
    onRestorePurchase: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        TextButton(
            onClick = onRestorePurchase,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.upgrade_screen_restore_purchase_action),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Preview2
@Composable
private fun UpgradeScreenPreview() {
    PreviewWrapper {
        UpgradeScreen(
            state = UpgradeViewModel.State(
                isLoadingPrices = false,
                iapState = UpgradeViewModel.State.Iap(
                    available = true,
                    formattedPrice = "$4.99",
                ),
                subState = UpgradeViewModel.State.Sub(
                    available = true,
                    formattedPrice = "$2.99",
                ),
                trialState = UpgradeViewModel.State.Trial(
                    available = true,
                    formattedPrice = "$2.99"
                ),
            ),
            onNavigateBack = {},
            onGoIap = {},
            onGoSubscription = {},
            onGoSubscriptionTrial = {},
            onRestorePurchase = {},
        )
    }
}

@Preview2
@Composable
private fun UpgradeScreenLoadingPreview() {
    PreviewWrapper {
        UpgradeScreen(
            state = UpgradeViewModel.State(
                isLoadingPrices = true,
                iapState = UpgradeViewModel.State.Iap(
                    available = false,
                    formattedPrice = null,
                ),
                subState = UpgradeViewModel.State.Sub(
                    available = false,
                    formattedPrice = null,
                ),
                trialState = UpgradeViewModel.State.Trial(
                    available = false,
                    formattedPrice = null
                ),
            ),
            onNavigateBack = {},
            onGoIap = {},
            onGoSubscription = {},
            onGoSubscriptionTrial = {},
            onRestorePurchase = {},
        )
    }
}

@Preview2Tablet
@Composable
private fun UpgradeScreenTabletPreview() {
    PreviewWrapper {
        UpgradeScreen(
            state = UpgradeViewModel.State(
                isLoadingPrices = false,
                iapState = UpgradeViewModel.State.Iap(
                    available = true,
                    formattedPrice = "$4.99",
                ),
                subState = UpgradeViewModel.State.Sub(
                    available = true,
                    formattedPrice = "$2.99",
                ),
                trialState = UpgradeViewModel.State.Trial(
                    available = true,
                    formattedPrice = "$2.99"
                ),
            ),
            onNavigateBack = {},
            onGoIap = {},
            onGoSubscription = {},
            onGoSubscriptionTrial = {},
            onRestorePurchase = {},
        )
    }
}

@Composable
fun RestoreFailedDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(eu.darken.butler.common.R.string.general_error_label),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = android.R.string.ok))
            }
        },
        text = {
            Text(
                text = """
                    ${stringResource(R.string.upgrade_screen_restore_purchase_message)}
                    
                    ${stringResource(R.string.upgrade_screen_restore_troubleshooting_msg)}
                    
                    ${stringResource(R.string.upgrade_screen_restore_sync_patience_hint)}
                    
                    ${stringResource(R.string.upgrade_screen_restore_multiaccount_hint)}
                """.trimIndent()
            )
        }
    )
}

@Preview2
@Composable
fun RestoreFailedDialogPreview() {
    PreviewWrapper {
        RestoreFailedDialog(
            onDismiss = {}
        )
    }
}