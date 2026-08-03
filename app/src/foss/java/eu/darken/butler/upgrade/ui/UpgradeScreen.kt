package eu.darken.butler.upgrade.ui

import android.widget.Toast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.AutoAwesome
import androidx.compose.material.icons.twotone.Favorite
import androidx.compose.material.icons.twotone.Info
import androidx.compose.material.icons.twotone.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.DateTimeStyle
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.formatDateTime
import eu.darken.butler.common.navigation.NavigationEventHandler
import kotlinx.coroutines.launch
import kotlin.time.Instant

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
    // Seeded from the ViewModel's handle-backed pending launch: after a process death while the
    // sponsor page was open, a blank tracker would swallow the very first return. The handle is the
    // authority on whether a return is still expected, so it reconstructs the tracker's state.
    val sponsorReturnTracker = remember(vm) {
        SponsorReturnTracker(wentToBackground = vm.hasPendingSponsorLaunch())
    }

    LaunchedEffect(Unit) {
        vm.snackbarEvent.collect { stringResId ->
            scope.launch { snackbarHostState.showSnackbar(context.getString(stringResId)) }
        }
    }

    LaunchedEffect(Unit) {
        vm.toastEvent.collect { stringResId ->
            Toast.makeText(context, context.getString(stringResId), Toast.LENGTH_LONG).show()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        sponsorReturnTracker.onStop()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (sponsorReturnTracker.consumeResumeReturn()) {
            vm.checkSponsorReturn()
        }
    }

    val state by vm.state.collectAsState(initial = null)

    UpgradeScreen(
        view = state?.view,
        supporterSince = state?.supporterSince,
        snackbarHostState = snackbarHostState,
        onNavigateUp = { vm.navUp() },
        onSponsor = { vm.openSponsor() },
        onRecurringSponsor = { vm.openRecurringSponsor() },
        onShowUpgradeOptions = { vm.onShowUpgradeOptions() },
    )
}

@Composable
internal fun UpgradeScreen(
    view: FossUpgradeView?,
    supporterSince: Instant? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onNavigateUp: () -> Unit = {},
    onSponsor: () -> Unit = {},
    onRecurringSponsor: () -> Unit = {},
    onShowUpgradeOptions: () -> Unit = {},
) {
    UpgradeScreenScaffold(
        title = {
            if (view == FossUpgradeView.PITCH || view == null) {
                Text(stringResource(R.string.upgrade_screen_title))
            } else {
                UpgradeTitle(upgraded = view == FossUpgradeView.STATUS_UPGRADED)
            }
        },
        onNavigateUp = onNavigateUp,
        snackbarHostState = snackbarHostState,
    ) { paddingValues ->
        when (view) {
            null -> Unit
            FossUpgradeView.PITCH -> PitchContent(paddingValues, onSponsor)
            FossUpgradeView.STATUS_FREE -> StatusFreeContent(paddingValues, onShowUpgradeOptions)
            FossUpgradeView.STATUS_UPGRADED -> StatusUpgradedContent(
                paddingValues = paddingValues,
                supporterSince = supporterSince,
                onRecurringSponsor = onRecurringSponsor,
            )
        }
    }
}

@Composable
private fun PitchContent(
    paddingValues: PaddingValues,
    onSponsor: () -> Unit,
) {
    UpgradeScreenContent(paddingValues = paddingValues) {
        UpgradeHeader(mascotSize = 104.dp)

        UpgradePreambleCard(
            text = stringResource(R.string.upgrade_screen_preamble),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        )

        UpgradeSectionCard(
            title = stringResource(R.string.upgrade_benefits_title),
            icon = Icons.TwoTone.AutoAwesome,
        ) {
            UpgradeBenefitsList()
        }

        UpgradeSectionCard(
            title = stringResource(R.string.upgrade_screen_how_title),
            icon = Icons.TwoTone.Favorite,
        ) {
            UpgradeSectionBody(text = stringResource(R.string.upgrade_screen_how_body))
        }

        UpgradeActionCard(
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ),
        ) {
            Button(
                onClick = onSponsor,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UpgradeScreenTags.FOSS_SPONSOR),
            ) { Text(stringResource(R.string.upgrade_screen_sponsor_action)) }

            UpgradeHintText(text = stringResource(R.string.upgrade_screen_sponsor_action_hint))
        }
    }
}

@Composable
private fun StatusFreeContent(
    paddingValues: PaddingValues,
    onShowUpgradeOptions: () -> Unit,
) {
    UpgradeScreenContent(paddingValues = paddingValues) {
        UpgradeHeader(mascotSize = 104.dp)

        UpgradeSectionCard(
            title = stringResource(R.string.upgrade_screen_status_free_title),
            icon = Icons.TwoTone.Info,
            modifier = Modifier.testTag(UpgradeScreenTags.FOSS_STATUS_FREE),
        ) {
            UpgradeSectionBody(text = stringResource(R.string.upgrade_screen_status_free_body))
            Button(
                onClick = onShowUpgradeOptions,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UpgradeScreenTags.FOSS_SHOW_OPTIONS),
            ) { Text(stringResource(R.string.upgrade_screen_status_free_action)) }
        }
    }
}

@Composable
private fun StatusUpgradedContent(
    paddingValues: PaddingValues,
    supporterSince: Instant?,
    onRecurringSponsor: () -> Unit,
) {
    UpgradeScreenContent(paddingValues = paddingValues) {
        UpgradeHeader(mascotSize = 104.dp)

        UpgradeSectionCard(
            title = stringResource(R.string.upgrade_screen_status_upgraded_title),
            icon = Icons.TwoTone.Verified,
            modifier = Modifier.testTag(UpgradeScreenTags.FOSS_STATUS_UPGRADED),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Text(
                text = stringResource(R.string.upgrade_screen_status_upgraded_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            supporterSince?.let { since ->
                val formatted = formatDateTime(since, DateTimeStyle.DATE_TEXTUAL)
                Text(
                    text = stringResource(R.string.upgrade_screen_supporter_since, formatted),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        UpgradeSectionCard(
            title = stringResource(R.string.upgrade_screen_recurring_title),
            icon = Icons.TwoTone.Favorite,
        ) {
            UpgradeSectionBody(text = stringResource(R.string.upgrade_screen_recurring_body))
            OutlinedButton(
                onClick = onRecurringSponsor,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UpgradeScreenTags.FOSS_DONATE),
            ) { Text(stringResource(R.string.upgrade_screen_recurring_action)) }
        }
    }
}

internal class SponsorReturnTracker(
    private var wentToBackground: Boolean = false,
) {

    fun onStop() {
        wentToBackground = true
    }

    fun consumeResumeReturn(): Boolean {
        return if (wentToBackground) {
            wentToBackground = false
            true
        } else {
            false
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun UpgradeScreenPitchPreview() {
    UpgradeScreen(view = FossUpgradeView.PITCH)
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun UpgradeScreenStatusFreePreview() {
    UpgradeScreen(view = FossUpgradeView.STATUS_FREE)
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun UpgradeScreenStatusUpgradedPreview() {
    UpgradeScreen(
        view = FossUpgradeView.STATUS_UPGRADED,
        supporterSince = Instant.fromEpochMilliseconds(1_700_000_000_000L),
    )
}
