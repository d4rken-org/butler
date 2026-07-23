package eu.darken.butler.upgrade.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Stars
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

// The acquisition offers box: header, subscription offer row, "or" divider, one-time offer row,
// parity footnote. Rendered inside an UpgradeActionCard by the caller.
@Composable
internal fun LoadedOffers(
    state: UpgradeUiState.Loaded,
    onIap: () -> Unit,
    onSubscription: () -> Unit,
    onSubscriptionTrial: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UpgradeScreenTags.ACTIONS),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        UpgradeSectionHeader(
            title = stringResource(R.string.upgrade_screen_offers_title),
            icon = Icons.TwoTone.Stars,
        )

        val subscriptionText = stringResource(
            if (state.subscriptionAction == UpgradeUiState.SubscriptionAction.TRIAL) {
                R.string.upgrade_screen_subscription_trial_action
            } else {
                R.string.upgrade_screen_subscription_action
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        UpgradeOfferRow(
            title = stringResource(R.string.upgrade_screen_subscription_offer_title),
            price = state.subscriptionPrice,
            hint = stringResource(
                if (state.subscriptionAction == UpgradeUiState.SubscriptionAction.TRIAL) {
                    R.string.upgrade_screen_subscription_offer_body
                } else {
                    R.string.upgrade_screen_subscription_offer_body_no_trial
                }
            ),
        ) {
            Button(
                onClick = if (state.subscriptionAction == UpgradeUiState.SubscriptionAction.TRIAL) {
                    onSubscriptionTrial
                } else {
                    onSubscription
                },
                enabled = state.subscriptionEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UpgradeScreenTags.SUBSCRIPTION),
            ) { Text(subscriptionText) }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f))
            Text(
                text = stringResource(R.string.upgrade_screen_offers_or),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(8.dp))

        UpgradeOfferRow(
            title = stringResource(R.string.upgrade_screen_iap_offer_title),
            price = state.iapPrice,
            hint = stringResource(R.string.upgrade_screen_iap_offer_body),
        ) {
            OutlinedButton(
                onClick = onIap,
                enabled = state.iapEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UpgradeScreenTags.IAP),
            ) {
                if (state.verificationInProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.upgrade_screen_iap_action))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        UpgradeHintText(text = stringResource(R.string.upgrade_screen_offers_body))
    }
}

// Title and price on one line (·-joined), terms as body text below, then the action button.
@Composable
internal fun UpgradeOfferRow(
    title: String,
    price: String?,
    modifier: Modifier = Modifier,
    hint: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = listOfNotNull(title, price).joinToString(" · "),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        hint?.let { UpgradeSectionBody(text = it) }
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun LoadedOffersPreview() {
    UpgradeActionCard {
        LoadedOffers(
            state = previewLoaded(),
            onIap = {},
            onSubscription = {},
            onSubscriptionTrial = {},
        )
    }
}

internal fun previewLoaded(
    ownership: UpgradeUiState.Ownership = UpgradeUiState.Ownership(),
    grace: UpgradeUiState.GraceHint? = null,
    wasPreviouslyPro: Boolean = false,
    subscriptionAction: UpgradeUiState.SubscriptionAction = UpgradeUiState.SubscriptionAction.TRIAL,
) = UpgradeUiState.Loaded(
    manage = ownership.ownsAnything,
    settled = true,
    ownership = ownership,
    grace = grace,
    subscriptionAction = subscriptionAction,
    subscriptionPrice = "$2.99",
    trialPrice = "$2.99",
    iapPrice = "$4.99",
    wasPreviouslyPro = wasPreviouslyPro,
    restoreInProgress = false,
    verificationInProgress = false,
)
