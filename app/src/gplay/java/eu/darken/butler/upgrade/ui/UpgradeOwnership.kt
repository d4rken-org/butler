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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerMascot
import eu.darken.butler.common.compose.ButlerMascotMode
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

@Composable
fun UpgradeOwnershipContent(
    modifier: Modifier = Modifier,
    state: UpgradeUiState.Loaded,
    onManageSubscription: () -> Unit,
    onSwitchToIap: () -> Unit,
    onRestorePurchase: () -> Unit,
) {
    val ownership = state.ownership
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OwnedHero(hasIap = ownership.hasIap)

        if (ownership.hasIap) {
            OwnedCard(
                title = stringResource(R.string.upgrade_screen_owned_iap_title),
                body = stringResource(R.string.upgrade_screen_owned_iap_body),
            )
        }

        ownership.subscription?.let { sub ->
            OwnedCard(
                title = stringResource(R.string.upgrade_screen_owned_sub_title),
                body = stringResource(
                    if (sub.isAutoRenewing) R.string.upgrade_screen_owned_sub_renewing_body
                    else R.string.upgrade_screen_owned_sub_not_renewing_body
                ),
            ) {
                if (ownership.hasIap && sub.isAutoRenewing) {
                    Text(
                        text = stringResource(R.string.upgrade_screen_owned_both_renewing_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedButton(
                    onClick = onManageSubscription,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.upgrade_screen_manage_subscription_action)) }
            }
        }

        if (state.showSwitchOffer) {
            SwitchOfferCard(state = state, onSwitchToIap = onSwitchToIap)
        }

        UpgradeRestoreSection(
            onRestorePurchase = onRestorePurchase,
            restoreInProgress = state.restoreInProgress,
        )
    }
}

@Composable
private fun OwnedHero(hasIap: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ButlerMascot(
            modifier = Modifier.size(72.dp),
            variant = ButlerMascotMode.Static.Happy(),
        )
        Column {
            Text(
                text = stringResource(R.string.upgrade_screen_owned_hero_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(
                    if (hasIap) R.string.upgrade_screen_owned_hero_iap_body
                    else R.string.upgrade_screen_owned_hero_sub_body
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun OwnedCard(
    title: String,
    body: String,
    content: @Composable (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
            content?.invoke()
        }
    }
}

// The sub->IAP switch: a locked offer until the subscription stops auto-renewing.
@Composable
private fun SwitchOfferCard(
    state: UpgradeUiState.Loaded,
    onSwitchToIap: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.upgrade_screen_switch_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.upgrade_screen_switch_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = onSwitchToIap,
                enabled = state.switchUnlocked,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag(UpgradeScreenTestTags.SWITCH_ACTION),
            ) {
                if (state.verificationInProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        stringResource(
                            R.string.upgrade_screen_switch_action,
                            state.iapPrice ?: "",
                        )
                    )
                }
            }
            Text(
                text = stringResource(
                    if (state.switchUnlocked) R.string.upgrade_screen_switch_purchase_note
                    else R.string.upgrade_screen_switch_locked_note
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun UpgradeOwnershipRenewingSubPreview() {
    UpgradeOwnershipContent(
        state = UpgradeUiState.Loaded(
            manage = true,
            settled = true,
            ownership = UpgradeUiState.Ownership(
                hasIap = false,
                subscription = UpgradeUiState.SubscriptionOwnership(isAutoRenewing = true),
            ),
            grace = null,
            subscriptionAction = UpgradeUiState.SubscriptionAction.UNAVAILABLE,
            subscriptionPrice = null,
            trialPrice = null,
            iapPrice = "$4.99",
            wasPreviouslyPro = false,
            restoreInProgress = false,
            verificationInProgress = false,
        ),
        onManageSubscription = {},
        onSwitchToIap = {},
        onRestorePurchase = {},
    )
}
