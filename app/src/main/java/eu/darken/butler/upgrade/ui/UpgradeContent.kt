package eu.darken.butler.upgrade.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerMascot
import eu.darken.butler.common.compose.ButlerMascotMode
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.brandTitle
import eu.darken.butler.workspace.ui.common.WorkspacePaddings

// Shared upgrade-screen primitives used by both the gplay and foss upgrade screens, mirroring
// SD Maid SE's upgrade design system: a centered, max-width scrolling column of tonal "section"
// and "action" cards, each led by an icon+title header, over a center-aligned top app bar.
internal object UpgradeScreenTags {
    const val TITLE = "upgrade_title"
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
    const val PENDING = "upgrade_pending"
    const val GRACE = "upgrade_grace"
    const val GRACE_SPINNER = "upgrade_grace_spinner"
    const val GRACE_RESTORE = "upgrade_grace_restore"
    const val FOSS_SPONSOR = "upgrade_foss_sponsor"
    const val FOSS_STATUS_FREE = "upgrade_foss_status_free"
    const val FOSS_STATUS_UPGRADED = "upgrade_foss_status_upgraded"
    const val FOSS_SHOW_OPTIONS = "upgrade_foss_show_options"
    const val FOSS_DONATE = "upgrade_foss_donate"
    const val HERO = "upgrade_hero"
}

// "Butler" + the flavor postfix, the postfix highlighted while the upgrade is active. The word
// order, separator and punctuation come from the flavor's title template so translators own them
// (see brandTitle) — never from locating a substring inside a combined name, which silently styles
// the wrong word once a locale reorders the two.
@Composable
internal fun upgradeScreenTitle(upgraded: Boolean): AnnotatedString = brandTitle(
    // Unconditional: this title names the flavor even when the screen is showing the free state.
    // The free state differs by the postfix wearing the plain color, not by losing the word.
    includeQualifier = true,
    highlightQualifier = upgraded,
)

// Marker char for brand-title splicing: formatted into the translated pattern via the normal
// Android format path (so %1$s vs %s, argument reordering, and %% all behave), then replaced
// with the styled brand. U+FFFC (object replacement) cannot occur in a real translation.
internal const val BRAND_TITLE_MARKER = "￼"

internal fun spliceBrandTitle(formatted: String, brand: AnnotatedString): AnnotatedString = buildAnnotatedString {
    var rest = formatted
    var found = false
    while (true) {
        val idx = rest.indexOf(BRAND_TITLE_MARKER)
        if (idx < 0) break
        found = true
        append(rest.substring(0, idx))
        append(brand)
        rest = rest.substring(idx + BRAND_TITLE_MARKER.length)
    }
    append(rest)
    if (!found) {
        // Defensive: a translation that lost its placeholder still shows the brand.
        append(" ")
        append(brand)
    }
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

@Composable
internal fun UpgradeScreenContent(
    paddingValues: PaddingValues,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = WorkspacePaddings.ScreenHorizontal,
        vertical = 24.dp,
    ),
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

private val HERO_GAP = 16.dp

// Below this much room for the copy the side-by-side split stops paying for itself: measured on a
// 320dp screen at 200% font, the row wrapped the preamble over 10 lines (breaking a word mid-way)
// and came out TALLER than stacking, which needs 6. Scaled by fontScale because the squeeze comes
// from text size as much as from screen width — at 200% font even a normal-width phone must stack.
private val HERO_MIN_TEXT_WIDTH = 150.dp

// The screen opener: mascot and preamble in one card instead of a floating icon stacked on a
// separate text box. Side-by-side keeps the mascot at eye level with the copy it introduces, and
// buys back the vertical space the standalone header used to spend above the fold — but only while
// the copy still has room to breathe, hence the stacked fallback.
@Composable
internal fun UpgradeHeroCard(
    text: String,
    modifier: Modifier = Modifier,
    mascotSize: Dp = 88.dp,
    happy: Boolean = true,
    colors: CardColors = CardDefaults.elevatedCardColors(),
) {
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag(UpgradeScreenTags.HERO),
        colors = colors,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .padding(end = 8.dp),
        ) {
            val minTextWidth = HERO_MIN_TEXT_WIDTH * LocalDensity.current.fontScale
            if (maxWidth - mascotSize - HERO_GAP < minTextWidth) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    UpgradeMascot(
                        size = mascotSize,
                        happy = happy,
                    )
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(HERO_GAP),
                ) {
                    UpgradeMascot(
                        size = mascotSize,
                        happy = happy,
                    )
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

// Preview copy matches the shipped preamble in length: the mascot/text split only reads correctly
// if the text wraps like it does in the app.
private const val PREVIEW_PREAMBLE =
    "Butler has no ads and doesn't sell user data. My work is financed by you ❤️."

// The screen pads its content column by 24dp horizontally, so the previews do too — the hero's
// branch threshold is measured against the width that actually remains for the card.
@Preview2
@Composable
private fun UpgradeHeroCardPreview() {
    PreviewWrapper {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            UpgradeHeroCard(text = PREVIEW_PREAMBLE)
        }
    }
}

// Preview2 only varies light/dark, so it can never reach the stacked branch. These two pin the
// thresholds that flip it: a narrow screen, and a normal-width screen at 200% font.
@Preview(showBackground = true, name = "Compact width", widthDp = 280)
@Preview(showBackground = true, name = "Huge font", fontScale = 2f)
@Composable
private fun UpgradeHeroCardCompactPreview() {
    PreviewWrapper {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            UpgradeHeroCard(text = PREVIEW_PREAMBLE)
        }
    }
}

// Both flavors tint the hero: FOSS on primaryContainer, GPLAY on secondaryContainer. Neither is
// the composable's default, so the default-colored preview above would not catch a contrast
// regression on the colors that actually ship.
@Preview2
@Composable
private fun UpgradeHeroCardTintedPreview() {
    PreviewWrapper {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            UpgradeHeroCard(
                text = PREVIEW_PREAMBLE,
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
            UpgradeHeroCard(
                text = PREVIEW_PREAMBLE,
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            )
        }
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

// Renders a feature blurb: bullet lines (leading • or -, some translations use hyphens) become
// checkmark rows, everything else stays plain paragraph text.
@Composable
internal fun UpgradeFeatureList(
    text: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                val bullet = line.startsWith("•") || line.startsWith("-")
                if (bullet) {
                    UpgradeFeatureRow(text = line.drop(1).trim())
                } else {
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
    }
}

@Composable
private fun UpgradeFeatureRow(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
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
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
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
