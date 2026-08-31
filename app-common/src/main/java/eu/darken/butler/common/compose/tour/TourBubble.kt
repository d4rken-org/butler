package eu.darken.butler.common.compose.tour

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.material.icons.automirrored.twotone.ArrowForward
import androidx.compose.material.icons.twotone.Check
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.takeOrElse
import eu.darken.butler.common.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerMascot
import eu.darken.butler.common.compose.ButlerMascotMode
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2

// The tail's vertical extent must stay in sync between SpeechBubbleShape (which draws the tail
// triangle outside the rounded-rect body) and SpeechBubbleSurface's content padding (which
// reserves matching space so content doesn't overlap the tail). Single source of truth.
private val TailHeight = 10.dp
private val MaxBubbleWidth = 480.dp
private val SideMargin = 16.dp
private val TargetGap = 16.dp
private val NarrowThreshold = 360.dp

// Fixed vertical chrome inside the bubble: 16dp top + 16dp bottom content padding, the 40dp control
// row, and the 16dp gap under the copy. The tail, where there is one, sits on top of this.
private val BubbleChrome = 88.dp

// Gap between a step's title and its body. Shared by StepContent and the height floors below so the
// space reserved for the copy and the space it actually occupies cannot drift apart.
private val TitleBodyGap = 4.dp

@Composable
internal fun TourBubble(
    step: TourStep,
    layout: StepLayout,
    session: TourSession,
    showConfirm: Boolean,
    onShowConfirmChange: (Boolean) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onDontShowAgain: () -> Unit,
    onDisableAllTours: () -> Unit,
    onFocusWithinChanged: (Boolean) -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val insets = WindowInsets.safeDrawing.asPaddingValues()
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current

        val isNarrow = maxWidth < NarrowThreshold
        val startPad = insets.calculateStartPadding(layoutDirection) + SideMargin
        val endPad = insets.calculateEndPadding(layoutDirection) + SideMargin
        val topPad = insets.calculateTopPadding() + SideMargin
        val bottomPad = insets.calculateBottomPadding() + SideMargin

        when (layout) {
            is StepLayout.Anchored -> AnchoredBubble(
                rect = layout.rect,
                step = step,
                session = session,
                showConfirm = showConfirm,
                onShowConfirmChange = onShowConfirmChange,
                isNarrow = isNarrow,
                density = density,
                insets = insets,
                maxWidth = maxWidth,
                maxHeight = maxHeight,
                startPad = startPad,
                endPad = endPad,
                onNext = onNext,
                onPrevious = onPrevious,
                onDontShowAgain = onDontShowAgain,
                onDisableAllTours = onDisableAllTours,
                onFocusWithinChanged = onFocusWithinChanged,
            )

            StepLayout.Centerless -> CenterlessBubble(
                step = step,
                session = session,
                showConfirm = showConfirm,
                onShowConfirmChange = onShowConfirmChange,
                isNarrow = isNarrow,
                maxHeight = maxHeight,
                startPad = startPad,
                endPad = endPad,
                topPad = topPad,
                bottomPad = bottomPad,
                onNext = onNext,
                onPrevious = onPrevious,
                onDontShowAgain = onDontShowAgain,
                onDisableAllTours = onDisableAllTours,
                onFocusWithinChanged = onFocusWithinChanged,
            )

            // Pending is filtered out by GuidedTourHost before this point.
            StepLayout.Pending -> Unit
        }
    }
}

@Composable
private fun BoxScope.AnchoredBubble(
    rect: Rect,
    step: TourStep,
    session: TourSession,
    showConfirm: Boolean,
    onShowConfirmChange: (Boolean) -> Unit,
    isNarrow: Boolean,
    density: Density,
    insets: PaddingValues,
    maxWidth: Dp,
    maxHeight: Dp,
    startPad: Dp,
    endPad: Dp,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onDontShowAgain: () -> Unit,
    onDisableAllTours: () -> Unit,
    onFocusWithinChanged: (Boolean) -> Unit,
) {
    val maxHPx = with(density) { maxHeight.toPx() }
    val maxWPx = with(density) { maxWidth.toPx() }
    val topInsetPx = with(density) { insets.calculateTopPadding().toPx() }
    val bottomInsetPx = with(density) { insets.calculateBottomPadding().toPx() }
    val gapPx = with(density) { TargetGap.toPx() }

    // Pick above/below by usable space, not by target center. Keeps long bubbles from
    // overflowing on tall targets that happen to sit just above the screen midpoint.
    val availableBelowPx = maxHPx - rect.bottom - bottomInsetPx - gapPx
    val availableAbovePx = rect.top - topInsetPx - gapPx
    val placeBelow = availableBelowPx >= availableAbovePx

    val startPadPx = with(density) { startPad.toPx() }
    val endPadPx = with(density) { endPad.toPx() }
    val maxBubbleWidthPx = with(density) { MaxBubbleWidth.toPx() }

    // The Surface fills available width minus side padding, capped at MaxBubbleWidth.
    // Compute the actual body width and its left edge, so the tail can target a real x.
    val availableWidthPx = (maxWPx - startPadPx - endPadPx).coerceAtLeast(1f)
    val bubbleBodyWidthPx = availableWidthPx.coerceAtMost(maxBubbleWidthPx)
    val bubbleBodyLeftPx = startPadPx + (availableWidthPx - bubbleBodyWidthPx) / 2f
    val tailXBias = ((rect.center.x - bubbleBodyLeftPx) / bubbleBodyWidthPx)
        .coerceIn(0f, 1f)

    val rawY = with(density) {
        if (placeBelow) {
            (rect.bottom + gapPx).toDp()
        } else {
            (maxHPx - rect.top + gapPx).toDp()
        }
    }
    // Far-side inset: keeps the bubble's *other* edge inside the safe area too.
    val farTopPad = insets.calculateTopPadding() + SideMargin
    val farBottomPad = insets.calculateBottomPadding() + SideMargin
    val nearInset = if (placeBelow) insets.calculateTopPadding() else insets.calculateBottomPadding()
    val farPad = if (placeBelow) farBottomPad else farTopPad

    // Buy the minimum height out of the offset, not out of heightIn: padding runs ahead of heightIn
    // in the same chain, so heightIn can only shrink what padding left over. Where the viewport
    // cannot hold both the gap and a readable bubble, the bubble slides over part of the cutout -
    // copy the user cannot read defeats the step, a partly covered spotlight does not.
    // The copy allowance is one line of each style the bubble renders, taken from the theme and
    // converted through the density the text itself uses, so it grows exactly as the text does. A
    // standalone sp value cannot: from API 34 font scaling is non-linear, and a large sp constant
    // lands deep in the damped region while the text it stands in for is still growing.
    // lineHeight may be unspecified in a restyled theme and toDp() only accepts Sp, hence the
    // fallback to the style's own font size.
    val typography = MaterialTheme.typography
    val wanted = with(density) {
        BubbleChrome + TailHeight +
            typography.titleMedium.lineHeight.takeOrElse { typography.titleMedium.fontSize }.toDp() +
            typography.bodyLarge.lineHeight.takeOrElse { typography.bodyLarge.fontSize }.toDp() +
            TitleBodyGap
    }
    val floor = wanted.coerceAtMost((maxHeight - farPad - nearInset).coerceAtLeast(0.dp))
    val clampedY = rawY
        .coerceAtLeast(nearInset)
        .coerceAtMost((maxHeight - farPad - floor).coerceAtLeast(nearInset))
    val bubbleMaxHeight = (maxHeight - clampedY - farPad).coerceAtLeast(0.dp)

    Box(
        modifier = Modifier
            .align(if (placeBelow) Alignment.TopCenter else Alignment.BottomCenter)
            .padding(
                top = if (placeBelow) clampedY else farTopPad,
                bottom = if (!placeBelow) clampedY else farBottomPad,
                start = startPad,
                end = endPad,
            )
            .widthIn(max = MaxBubbleWidth)
            .heightIn(max = bubbleMaxHeight),
    ) {
        BubbleCard(
            step = step,
            session = session,
            tail = BubbleTail.OnEdge(
                edge = if (placeBelow) SpeechBubbleShape.Edge.TOP else SpeechBubbleShape.Edge.BOTTOM,
                xBias = tailXBias,
            ),
            isNarrow = isNarrow,
            showConfirm = showConfirm,
            onShowConfirmChange = onShowConfirmChange,
            onNext = onNext,
            onPrevious = onPrevious,
            onDontShowAgain = onDontShowAgain,
            onDisableAllTours = onDisableAllTours,
            onFocusWithinChanged = onFocusWithinChanged,
        )
    }
}

@Composable
private fun BoxScope.CenterlessBubble(
    step: TourStep,
    session: TourSession,
    showConfirm: Boolean,
    onShowConfirmChange: (Boolean) -> Unit,
    isNarrow: Boolean,
    maxHeight: Dp,
    startPad: Dp,
    endPad: Dp,
    topPad: Dp,
    bottomPad: Dp,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onDontShowAgain: () -> Unit,
    onDisableAllTours: () -> Unit,
    onFocusWithinChanged: (Boolean) -> Unit,
) {
    // Cap height at the safe area; the body has its own vertical scroll for content that doesn't
    // fit. Same chain-order trap as AnchoredBubble: padding runs before heightIn, so the floor has
    // to be bought out of the two SideMargins. Never out of the raw insets - the bubble would slide
    // under the system bars. No tail here, so no tail height in the budget.
    val typography = MaterialTheme.typography
    val wanted = with(LocalDensity.current) {
        BubbleChrome +
            typography.titleMedium.lineHeight.takeOrElse { typography.titleMedium.fontSize }.toDp() +
            typography.bodyLarge.lineHeight.takeOrElse { typography.bodyLarge.fontSize }.toDp() +
            TitleBodyGap
    }
    val shortfall = (wanted - (maxHeight - topPad - bottomPad)).coerceAtLeast(0.dp)
    val giveBack = (shortfall / 2f).coerceAtMost(SideMargin)
    val effectiveTopPad = topPad - giveBack
    val effectiveBottomPad = bottomPad - giveBack
    val bubbleMaxHeight = (maxHeight - effectiveTopPad - effectiveBottomPad).coerceAtLeast(0.dp)
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .padding(start = startPad, end = endPad, top = effectiveTopPad, bottom = effectiveBottomPad)
            .widthIn(max = MaxBubbleWidth)
            .heightIn(max = bubbleMaxHeight),
    ) {
        BubbleCard(
            step = step,
            session = session,
            tail = BubbleTail.None,
            isNarrow = isNarrow,
            showConfirm = showConfirm,
            onShowConfirmChange = onShowConfirmChange,
            onNext = onNext,
            onPrevious = onPrevious,
            onDontShowAgain = onDontShowAgain,
            onDisableAllTours = onDisableAllTours,
            onFocusWithinChanged = onFocusWithinChanged,
        )
    }
}

internal sealed interface BubbleTail {
    data object None : BubbleTail
    data class OnEdge(val edge: SpeechBubbleShape.Edge, val xBias: Float) : BubbleTail
}

@Composable
private fun BubbleCard(
    step: TourStep,
    session: TourSession,
    tail: BubbleTail,
    isNarrow: Boolean,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onDontShowAgain: () -> Unit,
    onDisableAllTours: () -> Unit,
    onFocusWithinChanged: (Boolean) -> Unit = {},
    // Confirm state is hoisted to GuidedTourHost so its BackHandler can drive the same exit
    // confirm: back at the first step opens it, back while it's showing dismisses it.
    showConfirm: Boolean = false,
    onShowConfirmChange: (Boolean) -> Unit = {},
) {
    SpeechBubbleSurface(
        tail = tail,
        // Focus trap for D-pad/keyboard: once focus is inside the bubble it cycles among the
        // bubble's own controls and cannot wander into the scrimmed background. Entry happens
        // via the explicit focus requests in StepContent/ConfirmContent.
        modifier = Modifier
            .onFocusChanged { onFocusWithinChanged(it.hasFocus) }
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
    ) {
        AnimatedContent(
            targetState = showConfirm,
            transitionSpec = {
                fadeIn(tween(160)) togetherWith fadeOut(tween(120))
            },
            contentAlignment = Alignment.TopStart,
            label = "tour-bubble-content",
        ) { confirming ->
            if (confirming) {
                ConfirmContent(
                    onContinue = { onShowConfirmChange(false) },
                    onDontShowAgain = onDontShowAgain,
                    onDisableAllTours = onDisableAllTours,
                )
            } else {
                StepContent(
                    step = step,
                    session = session,
                    isNarrow = isNarrow,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    onRequestExit = { onShowConfirmChange(true) },
                )
            }
        }
    }
}

/**
 * Speech-bubble container: rounded surface with brand tint + border, an optional tail pointing
 * toward the cutout, and the inset padding that reserves space for the tail. Owns the
 * shape↔padding contract so callers don't have to. With [BubbleTail.None], no tail is drawn and
 * no tail-side padding is reserved (used for centerless intro/outro steps).
 */
@Composable
private fun SpeechBubbleSurface(
    tail: BubbleTail,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val tintedSurface = MaterialTheme.colorScheme.primary
        .copy(alpha = 0.06f)
        .compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh)
    val borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
    val tailSpec = (tail as? BubbleTail.OnEdge)?.let {
        SpeechBubbleShape.TailSpec(
            edge = it.edge,
            xBias = it.xBias,
            width = 16.dp,
            height = TailHeight,
        )
    }
    val shape = SpeechBubbleShape(
        cornerRadius = 20.dp,
        tail = tailSpec,
    )

    Surface(
        modifier = modifier,
        shape = shape,
        color = tintedSurface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = if (tailSpec?.edge == SpeechBubbleShape.Edge.TOP) TailHeight else 0.dp,
                    bottom = if (tailSpec?.edge == SpeechBubbleShape.Edge.BOTTOM) TailHeight else 0.dp,
                ),
            content = content,
        )
    }
}

@Composable
private fun StepContent(
    step: TourStep,
    session: TourSession,
    isNarrow: Boolean,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onRequestExit: () -> Unit,
) {
    val context = LocalContext.current
    val mascotWidth = if (isNarrow) 80.dp else 96.dp

    // Pull D-pad/keyboard focus into the bubble whenever the step view (re)appears — this is
    // what arms the focus trap on the surrounding focusGroup. Without it, TV focus stays in
    // the scrimmed background and the tour cannot be advanced at all.
    // Also keyed on the input mode: if the tour starts while in touch mode (screen opened via
    // tap), clickables aren't focusable and the initial request fails silently — the first
    // remote key press flips the mode to Keyboard and this re-runs to claim focus properly.
    val inputModeManager = LocalInputModeManager.current
    val nextFocus = remember { FocusRequester() }
    LaunchedEffect(step.stepId, inputModeManager.inputMode) {
        runCatching { nextFocus.requestFocus() }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = mascotWidth, end = 16.dp, top = 16.dp, bottom = 16.dp),
        ) {
            // Body sits above the controls so the step reads top-to-bottom: explanation first,
            // then the actions. Weighted (fill = false) so short copy stays compact against the
            // control row, while long copy is capped at the remaining height and scrolls — the
            // control row keeps its fixed height and stays pinned to the bubble's bottom edge.
            // Focusable so long step text can be scrolled with the D-pad from inside the focus
            // trap (scrollables handle arrow keys when focused). Tinted while focused since
            // plain focusable() has no indication of its own.
            var bodyFocused by remember { mutableStateOf(false) }
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(bottom = 16.dp)
                    .onFocusChanged { bodyFocused = it.isFocused }
                    .background(
                        color = if (bodyFocused) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                        } else {
                            Color.Transparent
                        },
                        shape = RoundedCornerShape(8.dp),
                    )
                    .verticalScroll(rememberScrollState())
                    .focusable(),
            ) {
                step.title?.let { title ->
                    Text(
                        text = title.get(context),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(TitleBodyGap))
                }
                Text(
                    text = step.body.get(context),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            // Controls: square nav buttons flank a flexible middle cell, so the step dots stay
            // centered in the free space between the left cluster (exit + back) and the Next
            // button — not pinned to the bubble's geometric center, which looked off-balance
            // once the clusters had unequal widths.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TourNavButton(
                    onClick = onRequestExit,
                    icon = Icons.TwoTone.Close,
                    contentDescription = stringResource(R.string.tour_action_cancel),
                )
                if (!session.isFirst) {
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = onPrevious,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.TwoTone.ArrowBack,
                            contentDescription = stringResource(R.string.tour_action_previous),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    if (session.definition.steps.size > 1) {
                        StepDots(
                            current = session.stepIndex,
                            total = session.definition.steps.size,
                        )
                    }
                }
                Button(
                    onClick = onNext,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.focusRequester(nextFocus),
                ) {
                    Icon(
                        imageVector = if (session.isLast) {
                            Icons.TwoTone.Check
                        } else {
                            Icons.AutoMirrored.TwoTone.ArrowForward
                        },
                        contentDescription = stringResource(
                            if (session.isLast) R.string.general_done_action else R.string.tour_action_next,
                        ),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        // Overlay the mascot in a matchParentSize box so it tracks the content column's height
        // rather than driving it. The mascot renders tall (portrait aspect); left free to set the
        // bubble height it would, on short steps, push the bubble past the content and leave slack
        // below the now-bottom control row. Bounded to fillMaxHeight it fits the content instead —
        // controls stay flush to the bottom edge, and on tall steps (content already taller than the
        // mascot) this is a no-op.
        Box(modifier = Modifier.matchParentSize()) {
            ButlerMascot(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(mascotWidth),
                // Wave once on the opening step, then hold the neutral pose. The bubble's job is to
                // point at the target, so a permanent second motion source beside the body text
                // would compete with it - the greeting is curated to the moment instead. Speed
                // matches the welcome screen so the two read as the same character. Stepping back to
                // the opening step replays the wave, which re-greets on the way back and is fine.
                variant = if (session.isFirst) {
                    ButlerMascotMode.Animated.Greeting(loop = false, speed = 1.2f)
                } else {
                    ButlerMascotMode.Static.Normal()
                },
            )
        }
    }
}

@Composable
private fun ConfirmContent(
    onContinue: () -> Unit,
    onDontShowAgain: () -> Unit,
    onDisableAllTours: () -> Unit,
) {
    // Mirror of StepContent's focus pull: when the confirm view swaps in via AnimatedContent,
    // re-anchor D-pad focus on the safe default so the trap keeps holding. Keyed on input mode
    // for the same touch-mode-start reason as StepContent.
    val inputModeManager = LocalInputModeManager.current
    val continueFocus = remember { FocusRequester() }
    LaunchedEffect(inputModeManager.inputMode) {
        runCatching { continueFocus.requestFocus() }
    }

    // Actions are stacked full-width (not a Row): the labels are too long to share a row at large
    // font scale / narrow widths / translations, and each full-width button is its own focusable so
    // the D-pad can reach the bottom one (the scroll follows focus). The escalation reads top→bottom:
    // keep going → silence this tour → silence everything.
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.tour_confirm_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.tour_confirm_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(continueFocus),
        ) {
            Text(text = stringResource(R.string.tour_confirm_continue))
        }
        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = onDontShowAgain,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.tour_confirm_dont_show_this_tour))
        }
        TextButton(
            onClick = onDisableAllTours,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text(text = stringResource(R.string.tour_confirm_disable_all))
        }
    }
}

/**
 * Compact round button for the tour header's close (✕) action. A bare [Button] enforces a
 * ~58.dp min width (stadium pill); the fixed [size] overrides that so close renders as a small
 * icon-sized circle, distinct from the wider prev/next pills.
 */
@Composable
private fun TourNavButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        contentPadding = PaddingValues(0.dp),
        modifier = modifier.size(40.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun StepDots(
    current: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(total) { i ->
            val isActive = i == current
            Box(
                modifier = Modifier
                    .size(if (isActive) 8.dp else 6.dp)
                    .background(
                        color = if (isActive) activeColor else idleColor,
                        shape = CircleShape,
                    ),
            )
        }
    }
}

// region Previews

private val previewStep = TourStep(
    stepId = "preview",
    title = "Tabs".toCaString(),
    body = (
        "Butler works in tabs, like a browser. Each tab can be an explorer, a search, " +
            "an editor, and more."
        ).toCaString(),
)

private val previewSession = TourSession(
    definition = TourDefinition(
        id = TourId("preview.tour"),
        steps = List(4) { i ->
            TourStep(
                stepId = "step$i",
                title = "Step ${i + 1}".toCaString(),
                body = "Body of step ${i + 1}".toCaString(),
            )
        },
    ),
    stepIndex = 0,
)

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun StepDotsPreviewFirst() {
    Box(modifier = Modifier.padding(16.dp)) {
        StepDots(current = 0, total = 4)
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun StepDotsPreviewMiddle() {
    Box(modifier = Modifier.padding(16.dp)) {
        StepDots(current = 2, total = 4)
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun StepContentPreview() {
    StepContent(
        step = previewStep,
        session = previewSession,
        isNarrow = false,
        onNext = {},
        onPrevious = {},
        onRequestExit = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun StepContentPreviewLastStep() {
    StepContent(
        step = previewStep,
        session = previewSession.copy(stepIndex = 3),
        isNarrow = false,
        onNext = {},
        onPrevious = {},
        onRequestExit = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun StepContentPreviewSingleStep() {
    StepContent(
        step = previewStep,
        session = TourSession(
            definition = TourDefinition(
                id = TourId("preview.tour.single"),
                steps = listOf(previewStep),
            ),
            stepIndex = 0,
        ),
        isNarrow = false,
        onNext = {},
        onPrevious = {},
        onRequestExit = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun StepContentPreviewNarrow() {
    StepContent(
        step = previewStep,
        session = previewSession,
        isNarrow = true,
        onNext = {},
        onPrevious = {},
        onRequestExit = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ConfirmContentPreview() {
    ConfirmContent(
        onContinue = {},
        onDontShowAgain = {},
        onDisableAllTours = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun BubbleCardPreviewTailTop() {
    Box(modifier = Modifier.padding(16.dp)) {
        BubbleCard(
            step = previewStep,
            session = previewSession,
            tail = BubbleTail.OnEdge(edge = SpeechBubbleShape.Edge.TOP, xBias = 0.5f),
            isNarrow = false,
            onNext = {},
            onPrevious = {},
            onDontShowAgain = {},
            onDisableAllTours = {},
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun BubbleCardPreviewTailBottom() {
    Box(modifier = Modifier.padding(16.dp)) {
        BubbleCard(
            step = previewStep,
            session = previewSession.copy(stepIndex = 1),
            tail = BubbleTail.OnEdge(edge = SpeechBubbleShape.Edge.BOTTOM, xBias = 0.3f),
            isNarrow = false,
            onNext = {},
            onPrevious = {},
            onDontShowAgain = {},
            onDisableAllTours = {},
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun BubbleCardPreviewCenterless() {
    Box(modifier = Modifier.padding(16.dp)) {
        BubbleCard(
            step = previewStep,
            session = previewSession,
            tail = BubbleTail.None,
            isNarrow = false,
            onNext = {},
            onPrevious = {},
            onDontShowAgain = {},
            onDisableAllTours = {},
        )
    }
}

// endregion
