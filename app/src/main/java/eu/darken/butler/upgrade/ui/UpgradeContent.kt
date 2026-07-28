package eu.darken.butler.upgrade.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.CheckCircle
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerMascot
import eu.darken.butler.common.compose.ButlerMascotMode
import eu.darken.butler.common.compose.ColoredTitleText

// Shared upgrade-screen primitives used by both the gplay and foss upgrade screens, mirroring
// SD Maid SE's upgrade design system: a centered, max-width scrolling column of tonal "section"
// and "action" cards, each led by an icon+title header, over a center-aligned top app bar.
internal object UpgradeScreenTags {
    const val LOADING = "upgrade_loading"
    const val ACTIONS = "upgrade_actions"
    const val MASCOT_HAPPY = "upgrade_mascot_happy"
    const val MASCOT_GRUMPY = "upgrade_mascot_grumpy"
    const val SUBSCRIPTION = "upgrade_subscription"
    const val SUBSCRIPTION_SPINNER = "upgrade_subscription_spinner"
    const val IAP = "upgrade_iap"
    const val IAP_SPINNER = "upgrade_iap_spinner"
    const val RESTORE = "upgrade_restore"
    const val RESTORE_BANNER = "upgrade_restore_banner"
    const val RESTORE_BANNER_ACTION = "upgrade_restore_banner_action"
    const val UNAVAILABLE = "upgrade_unavailable"
    const val RETRY = "upgrade_retry"
    const val OWNED_HERO = "upgrade_owned_hero"
    const val OWNED_IAP = "upgrade_owned_iap"
    const val OWNED_SUB = "upgrade_owned_sub"
    const val MANAGE_SUB = "upgrade_manage_sub"
    const val GRACE = "upgrade_grace"
    const val GRACE_SPINNER = "upgrade_grace_spinner"
    const val GRACE_RESTORE = "upgrade_grace_restore"
    const val FOSS_SPONSOR = "upgrade_foss_sponsor"
    const val FOSS_STATUS_FREE = "upgrade_foss_status_free"
    const val FOSS_STATUS_UPGRADED = "upgrade_foss_status_upgraded"
    const val FOSS_SHOW_OPTIONS = "upgrade_foss_show_options"
    const val FOSS_DONATE = "upgrade_foss_donate"
}

@Composable
internal fun UpgradeScreenScaffold(
    title: @Composable () -> Unit,
    onNavigateUp: () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = title,
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(eu.darken.butler.common.R.string.general_back_action),
                        )
                    }
                },
            )
        },
        snackbarHost = { snackbarHostState?.let { SnackbarHost(it) } },
        content = content,
    )
}

// The composed "Butler Pro"/"Butler FOSS" title with the flavor postfix highlighted, used on the
// owner/grace/status screens; the plain acquisition pitch uses a normal Text title instead.
@Composable
internal fun UpgradeTitle() {
    ColoredTitleText(
        fullTitle = stringResource(R.string.app_name_upgraded),
        postfix = stringResource(R.string.app_name_upgrade_postfix),
        style = MaterialTheme.typography.titleLarge,
    )
}

@Composable
internal fun UpgradeScreenContent(
    paddingValues: PaddingValues,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(paddingValues)
            .verticalScroll(rememberScrollState()),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
        }
    }
}

@Composable
internal fun UpgradeMascot(
    size: Dp,
    modifier: Modifier = Modifier,
    happy: Boolean = true,
) {
    ButlerMascot(
        // Tagged by mood: the grace stages swap the face, and that swap is asserted in the tests.
        modifier = modifier
            .size(size)
            .testTag(if (happy) UpgradeScreenTags.MASCOT_HAPPY else UpgradeScreenTags.MASCOT_GRUMPY),
        variant = if (happy) ButlerMascotMode.Static.Happy() else ButlerMascotMode.Static.Sad(),
    )
}

@Composable
internal fun UpgradeHeader(
    mascotSize: Dp,
    modifier: Modifier = Modifier,
    happy: Boolean = true,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
            shape = CircleShape,
        ) {
            UpgradeMascot(
                size = mascotSize,
                modifier = Modifier.padding(16.dp),
                happy = happy,
            )
        }
    }
}

@Composable
internal fun UpgradePreambleCard(
    text: String,
    modifier: Modifier = Modifier,
    colors: CardColors = CardDefaults.elevatedCardColors(),
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = colors,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        )
    }
}

@Composable
internal fun UpgradeSectionCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    iconTint: Color = Color.Unspecified,
    colors: CardColors? = null,
    leading: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cardColors = colors ?: CardDefaults.elevatedCardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    )
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = cardColors,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UpgradeSectionHeader(title = title, icon = icon, iconTint = iconTint, leading = leading)
            content()
        }
    }
}

@Composable
internal fun UpgradeSectionHeader(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    iconTint: Color = Color.Unspecified,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (leading != null) {
            leading()
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (iconTint == Color.Unspecified) MaterialTheme.colorScheme.primary else iconTint,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun UpgradeSectionBody(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
    )
}

// Butler's Pro benefits rendered as SD-Maid-style check-icon feature rows. Uses the existing
// per-flavor benefit strings (kept for their translations) instead of one parsed multiline blob.
@Composable
internal fun UpgradeBenefitsList(modifier: Modifier = Modifier) {
    val benefits = listOf(
        R.string.upgrade_benefit_multitasking,
        R.string.upgrade_benefit_customization,
        R.string.upgrade_benefit_extra_options,
        R.string.upgrade_benefit_early_access,
        R.string.upgrade_benefit_motivation,
        R.string.upgrade_benefit_and_more,
    )
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        benefits.forEach { res ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.TwoTone.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(18.dp),
                )
                Text(
                    text = stringResource(res),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
internal fun UpgradeHintText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
internal fun UpgradeActionCard(
    modifier: Modifier = Modifier,
    colors: CardColors? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cardColors = colors ?: CardDefaults.elevatedCardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = cardColors,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
                .animateContentSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
internal fun UpgradeLoadingBlock(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp)
            .testTag(UpgradeScreenTags.LOADING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
    }
}

@Composable
internal fun UpgradeInlineStateCard(
    title: String,
    body: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    UpgradeSectionCard(
        title = title,
        icon = icon,
        modifier = modifier.testTag(UpgradeScreenTags.UNAVAILABLE),
        iconTint = MaterialTheme.colorScheme.onErrorContainer,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        content()
    }
}
