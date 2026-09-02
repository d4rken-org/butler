package eu.darken.butler.common.compose

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.WorkspacePremium
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import eu.darken.butler.common.R
import eu.darken.butler.common.navigation.LocalNavigationController
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.upgrade
import eu.darken.butler.upgrade.UpgradeRepo
import kotlinx.coroutines.flow.Flow

/**
 * Wraps a Pro-only feature: renders [content] for Pro users, and for everyone else renders the same
 * content obscured behind an upgrade prompt.
 *
 * The content is composed and drawn either way - that is what the blur has to work on - so what the
 * free user sees is the real thing with its detail taken away rather than a mock-up. Two
 * consequences the caller has to know about:
 * - A free user pays the content's full cost: its data flows, its layout, its draw. There is no
 *   version of this that skips the work, so keep the gated slot to a presentation layer whose cost
 *   you would accept running for everyone.
 * - Only Android 12+ shows a blurred teaser of the content; older devices get an empty panel, see
 *   [obscure]. A layout that would break with nothing drawn in it does not belong here.
 *
 * Obscuring is presentation, not a secret: a blur is reversible in principle, and the content is
 * still in the view tree. Gate what is worth paying for, never what must not be seen.
 *
 * @param isPro override for previews and tests; by default this reads the app's upgrade state.
 * @param description one line naming what the feature is, shown under the tier label. Optional -
 *        without it the prompt is the tier label plus the upgrade action.
 */
@Composable
fun ProGate(
    modifier: Modifier = Modifier,
    isPro: Boolean = rememberIsPro(),
    description: String? = null,
    onUpgrade: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val navController = LocalNavigationController.current
    val upgradeAction = onUpgrade ?: navController?.let { { it.goTo(Nav.Main.upgrade()) } }

    // One wrapper in both states, so [modifier] means the same thing either way and an entitlement
    // that resolves late moves the content's `remember` state and effects nowhere.
    Box(modifier = modifier) {
        Box(
            modifier = when {
                isPro -> Modifier
                else -> Modifier
                    .obscure()
                    // The content is still laid out, so without this it stays readable to TalkBack.
                    .clearAndSetSemantics { }
                    // canFocus alone only deactivates this node - focus search then walks its
                    // children instead. Cancelling the enter is what keeps a D-pad or a keyboard
                    // out of gated controls.
                    .focusProperties {
                        canFocus = false
                        onEnter = { cancelFocusChange() }
                    }
                    .focusGroup()
            },
        ) {
            content()
        }

        if (!isPro) {
            // Nothing below the prompt is here to be touched. The blocker rides the scrim rather
            // than the content, so it also covers the margin a prompt larger than the content adds.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = SCRIM_ALPHA))
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        }
                    },
            )

            // Sizes the gate together with the content rather than matching it: gating something
            // smaller than the prompt itself must grow the gate, not clip the way out of it.
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    modifier = Modifier.size(28.dp),
                    imageVector = Icons.TwoTone.WorkspacePremium,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    text = brandTitleText(includeQualifier = true),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                if (upgradeAction != null) {
                    FilledTonalButton(onClick = upgradeAction) {
                        Text(text = stringResource(R.string.general_upgrade_action))
                    }
                }
            }
        }
    }
}

/**
 * Hides the gated content's detail.
 *
 * `Modifier.blur` is a RenderEffect, which only exists from Android 12 on; below that it is
 * silently ignored. A scrim does not stand in for it: measured on an API 30 emulator, this chart's
 * axis values, speed chips and curve all stayed plainly readable through 0.93 alpha, because the
 * content is dark-on-light and 7% of a large contrast delta is still a large contrast delta.
 *
 * So older devices skip the draw entirely - laid out and measured, never rasterized. The gate keeps
 * its size and the free user sees an empty panel there instead of a blurred one.
 */
private fun Modifier.obscure(): Modifier = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> blur(BLUR_RADIUS)
    else -> drawWithContent { /* deliberately never calls drawContent() */ }
}

private const val SCRIM_ALPHA = 0.6f

private val BLUR_RADIUS: Dp = 16.dp

/**
 * Whether an upgrade state should render as Pro.
 *
 * Fails open on everything that is not a clean "settled, no purchase": no emission yet, an
 * unsettled one (the GPlay cold-start window, where a paying user still reads as non-Pro) and a
 * settled one carrying a read error all count as Pro. This is the same generosity
 * `UpgradeRepo.isProSettled` applies, and for the same reason - a billing hiccup must never bill a
 * paying user twice in attention. It is NOT `isProForUi`, which gives an unsettled state a bounded
 * wait and then denies; nothing here is an entitlement boundary, so there is nothing to deny.
 */
internal fun UpgradeRepo.Info?.rendersAsPro(): Boolean = when {
    this == null -> true
    isPro -> true
    !isSettled -> true
    error != null -> true
    else -> false
}

/** The app's current Pro state, for UI that routes between a gated and an ungated presentation. */
@Composable
fun rememberIsPro(): Boolean {
    val context = LocalContext.current
    val upgradeInfo: Flow<UpgradeRepo.Info>? = remember(context) {
        runCatching {
            EntryPointAccessors
                .fromApplication(context.applicationContext, ProGateEntryPoint::class.java)
                .upgradeRepo()
                .upgradeInfo
        }.getOrNull()
    }
    if (upgradeInfo == null) return true

    val info by upgradeInfo.collectAsState(initial = null)
    return info.rendersAsPro()
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ProGateEntryPoint {
    fun upgradeRepo(): UpgradeRepo
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ProGateGatedPreview() {
    ProGate(
        isPro = false,
        description = "See how fast this ran.",
        onUpgrade = {},
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ProGateGatedWithoutDescriptionPreview() {
    ProGate(
        isPro = false,
        onUpgrade = {},
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ProGateUnlockedPreview() {
    ProGate(isPro = true) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}
