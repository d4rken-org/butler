package eu.darken.butler.upgrade.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Autorenew
import androidx.compose.material.icons.twotone.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

// Ownership presentation for users who already own Pro. A subscriber without the one-time purchase
// always sees the switch offer — LOCKED while the subscription still renews.
@Composable
internal fun UpgradeOwnershipContent(
    state: UpgradeUiState.Loaded,
    onIap: () -> Unit,
    onManageSubscription: () -> Unit,
    onRestore: () -> Unit,
) {
    val ownership = state.ownership
    val subscription = ownership.subscription

    UpgradeOwnedHero(ownership = ownership)

    if (ownership.hasIap) {
        UpgradeSectionCard(
            title = stringResource(R.string.upgrade_screen_owned_iap_title),
            icon = Icons.TwoTone.Verified,
            modifier = Modifier.testTag(UpgradeScreenTags.OWNED_IAP),
        ) {
            UpgradeSectionBody(text = stringResource(R.string.upgrade_screen_owned_iap_body))
        }
    }

    if (subscription != null) {
        UpgradeSectionCard(
            title = stringResource(R.string.upgrade_screen_owned_sub_title),
            icon = Icons.TwoTone.Autorenew,
            modifier = Modifier.testTag(UpgradeScreenTags.OWNED_SUB),
        ) {
            UpgradeSectionBody(
                text = stringResource(
                    if (subscription.isAutoRenewing) R.string.upgrade_screen_owned_sub_renewing_body
                    else R.string.upgrade_screen_owned_sub_not_renewing_body
                ),
            )
            if (subscription.isAutoRenewing && ownership.hasIap) {
                Text(
                    text = stringResource(R.string.upgrade_screen_owned_both_renewing_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(
                onClick = onManageSubscription,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UpgradeScreenTags.MANAGE_SUB),
            ) { Text(stringResource(R.string.upgrade_screen_manage_subscription_action)) }
        }
    }

    if (subscription != null && !ownership.hasIap) {
        val switchUnlocked = !subscription.isAutoRenewing
        UpgradeActionCard {
            UpgradeOfferRow(
                title = stringResource(R.string.upgrade_screen_iap_offer_title),
                price = state.iapPrice,
                hint = stringResource(
                    if (switchUnlocked) R.string.upgrade_screen_switch_purchase_note
                    else R.string.upgrade_screen_switch_locked_note
                ),
            ) {
                Button(
                    onClick = onIap,
                    // Not gated on iapEnabled: prices may have failed to load while the purchase
                    // itself would work (the billing flow re-queries details on launch). The
                    // fail-closed SUBS verification happens on tap in the ViewModel.
                    enabled = switchUnlocked && !state.verificationInProgress && !state.restoreInProgress,
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
        }
    }

    UpgradeRestoreSection(
        title = stringResource(R.string.upgrade_screen_restore_status_title),
        body = stringResource(R.string.upgrade_screen_restore_status_body),
        onRestore = onRestore,
        restoreInProgress = state.restoreInProgress,
    )
}

// The "you have it" moment: mascot + congrats in one hero card, variant (sub vs one-time) spelled
// out; the per-purchase cards below carry the details and actions.
@Composable
private fun UpgradeOwnedHero(
    ownership: UpgradeUiState.Ownership,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag(UpgradeScreenTags.OWNED_HERO),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            UpgradeMascot(size = 56.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.upgrade_screen_owned_hero_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(
                        if (ownership.hasIap) R.string.upgrade_screen_owned_hero_iap_body
                        else R.string.upgrade_screen_owned_hero_sub_body
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

// Shown on the acquisition view while Pro is active purely via grace. Calm reassurance, not a
// warning. Stage 1 confirms Pro is intact (spinner header); stage 2 (aged) explains + offers restore.
@Composable
internal fun UpgradeGraceCard(
    showDiagnostics: Boolean,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
    restoreInProgress: Boolean = false,
) {
    UpgradeSectionCard(
        title = stringResource(R.string.upgrade_screen_grace_title),
        icon = Icons.TwoTone.Verified,
        modifier = modifier.testTag(UpgradeScreenTags.GRACE),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        leading = if (showDiagnostics) null else {
            {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(24.dp)
                        .testTag(UpgradeScreenTags.GRACE_SPINNER),
                    strokeWidth = 2.5.dp,
                )
            }
        },
    ) {
        Text(
            text = stringResource(
                if (showDiagnostics) R.string.upgrade_screen_grace_body
                else R.string.upgrade_screen_grace_body_short
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (showDiagnostics) {
            Button(
                onClick = onRestore,
                enabled = !restoreInProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UpgradeScreenTags.GRACE_RESTORE),
            ) {
                if (restoreInProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.upgrade_screen_restore_purchase_action))
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun UpgradeOwnershipRenewingSubPreview() {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        UpgradeOwnershipContent(
            state = previewLoaded(
                ownership = UpgradeUiState.Ownership(
                    subscription = UpgradeUiState.SubscriptionOwnership(isAutoRenewing = true),
                ),
            ),
            onIap = {},
            onManageSubscription = {},
            onRestore = {},
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun UpgradeOwnershipNonRenewingSubPreview() {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        UpgradeOwnershipContent(
            state = previewLoaded(
                ownership = UpgradeUiState.Ownership(
                    subscription = UpgradeUiState.SubscriptionOwnership(isAutoRenewing = false),
                ),
            ),
            onIap = {},
            onManageSubscription = {},
            onRestore = {},
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun UpgradeGraceCardQuietPreview() {
    UpgradeGraceCard(showDiagnostics = false, onRestore = {})
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun UpgradeGraceCardDiagnosticsPreview() {
    UpgradeGraceCard(showDiagnostics = true, onRestore = {})
}
