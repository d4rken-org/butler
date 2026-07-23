package eu.darken.butler.upgrade.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

@Composable
fun PreambleCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        ),
    ) {
        Text(
            text = stringResource(R.string.upgrade_screen_preamble),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
fun BenefitsList(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
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

// The acquisition offer box: subscription (trial/standard) + one-time purchase, separated by an "or".
@Composable
fun AcquisitionOffers(
    modifier: Modifier = Modifier,
    state: UpgradeUiState.Loaded,
    onGoSubscription: () -> Unit,
    onGoSubscriptionTrial: () -> Unit,
    onGoIap: () -> Unit,
) {
    val nothingAvailable = !state.subscriptionAvailable && !state.iapAvailable
    if (nothingAvailable) {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
            ),
        ) {
            Text(
                text = stringResource(R.string.upgrades_gplay_unavailable_error_description),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (state.subscriptionAvailable) {
            if (state.subscriptionAction == UpgradeUiState.SubscriptionAction.TRIAL) {
                Button(
                    onClick = onGoSubscriptionTrial,
                    enabled = state.subscriptionEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag(UpgradeScreenTestTags.SUB_ACTION),
                ) { Text(stringResource(R.string.upgrade_screen_subscription_trial_action)) }
            } else {
                OutlinedButton(
                    onClick = onGoSubscription,
                    enabled = state.subscriptionEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag(UpgradeScreenTestTags.SUB_ACTION),
                ) { Text(stringResource(R.string.upgrade_screen_subscription_action)) }
            }
            Text(
                text = stringResource(
                    R.string.upgrade_screen_subscription_action_hint,
                    state.subscriptionPrice ?: "",
                ),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.subscriptionAvailable && state.iapAvailable) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.upgrade_screen_offers_or),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(modifier = Modifier.weight(1f))
            }
        }

        if (state.iapAvailable) {
            OutlinedButton(
                onClick = onGoIap,
                enabled = state.iapEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag(UpgradeScreenTestTags.IAP_ACTION),
            ) {
                if (state.verificationInProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.upgrade_screen_iap_action))
                }
            }
            Text(
                text = stringResource(R.string.upgrade_screen_iap_action_hint, state.iapPrice ?: ""),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// Two-stage grace card: quiet "confirming" spinner stage, then after 24h the diagnostics stage with a
// restore button. Offers are brought back separately (aged stage) by the caller.
@Composable
fun GraceCard(
    modifier: Modifier = Modifier,
    grace: UpgradeUiState.GraceHint,
    restoreInProgress: Boolean,
    onRestorePurchase: () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!grace.showDiagnostics) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = stringResource(R.string.upgrade_screen_grace_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                text = stringResource(
                    if (grace.showDiagnostics) R.string.upgrade_screen_grace_body
                    else R.string.upgrade_screen_grace_body_short
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (grace.showDiagnostics) {
                UpgradeRestoreSection(
                    onRestorePurchase = onRestorePurchase,
                    restoreInProgress = restoreInProgress,
                )
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun GraceCardQuietPreview() {
    GraceCard(
        grace = UpgradeUiState.GraceHint(showDiagnostics = false),
        restoreInProgress = false,
        onRestorePurchase = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun GraceCardDiagnosticsPreview() {
    GraceCard(
        grace = UpgradeUiState.GraceHint(showDiagnostics = true),
        restoreInProgress = false,
        onRestorePurchase = {},
    )
}
