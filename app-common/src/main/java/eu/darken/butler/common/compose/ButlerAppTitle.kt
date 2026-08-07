package eu.darken.butler.common.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.R
import eu.darken.butler.common.compose.ButlerPreviewWrapper

// The app's brand title, composed through the flavor's title template so translators own the word
// order and punctuation instead of the code assuming "name, space, qualifier". Composed from TWO
// resources, never by locating the qualifier inside a combined name: localized upgrade words
// reorder, and substring math silently styles the wrong word when they do.
//
// The two flags are deliberately separate. `includeQualifier` decides whether the tier word is part
// of the title at all (the plain app title drops it while free); `highlightQualifier` only decides
// whether it is colored. The FOSS status-free view needs "Butler FOSS" in one uniform color, so it
// passes (true, false) — collapsing these into one flag would silently drop FOSS from that screen.
//
// Butler's own identity keeps its primary-colored base in every combination; only the qualifier
// changes color, which is what says "you have this".
@Composable
fun brandTitle(includeQualifier: Boolean, highlightQualifier: Boolean): AnnotatedString {
    val name = buildAnnotatedString {
        pushStyle(SpanStyle(color = MaterialTheme.colorScheme.primary))
        append(stringResource(R.string.app_name))
        pop()
    }
    if (!includeQualifier) return name

    val qualifier = buildAnnotatedString {
        pushStyle(
            SpanStyle(
                color = when {
                    highlightQualifier -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                },
            )
        )
        append(stringResource(R.string.app_name_upgrade_postfix))
        pop()
    }
    return spliceTitleTemplate(
        formatted = stringResource(
            R.string.app_name_upgraded_template,
            BRAND_TITLE_MARKER,
            BRAND_QUALIFIER_MARKER,
        ),
        name = name,
        qualifier = qualifier,
    )
}

// Same composition for call sites that need a plain String. Routed through brandTitle so the two
// forms cannot drift apart.
@Composable
fun brandTitleText(includeQualifier: Boolean): String =
    brandTitle(includeQualifier = includeQualifier, highlightQualifier = false).text

// Marker chars for title splicing: formatted into the translated template via the normal Android
// format path (so %1$s vs %s, argument reordering, and %% all behave), then replaced with the
// styled parts. U+FFFC (object replacement) cannot occur in a real translation, and U+FFF9
// (interlinear annotation anchor) is likewise absent — being distinct is what lets the splice tell
// the two slots apart after the formatter has reordered them.
internal const val BRAND_TITLE_MARKER = "￼"
internal const val BRAND_QUALIFIER_MARKER = "￹"

// Splices the two title slots into an already-formatted template. Strict on purpose: a title
// template has exactly two slots, so anything else is damage — and once a slot is missing or
// doubled the template can no longer tell us the intended order or punctuation, which is the whole
// reason it exists. So a broken template is discarded whole and the default title is rebuilt from
// the parts; patching it up piecewise would emit a title no translator wrote.
internal fun spliceTitleTemplate(
    formatted: String,
    name: AnnotatedString,
    qualifier: AnnotatedString,
): AnnotatedString {
    val slots = listOf(
        BRAND_TITLE_MARKER to name,
        BRAND_QUALIFIER_MARKER to qualifier,
    ).map { (marker, value) -> Triple(formatted.indexOf(marker), marker, value) }

    val intact = slots.all { (index, marker, _) ->
        index >= 0 && formatted.indexOf(marker, index + marker.length) < 0
    }
    if (!intact) {
        return buildAnnotatedString {
            append(name)
            append(" ")
            append(qualifier)
        }
    }

    return buildAnnotatedString {
        var cursor = 0
        slots.sortedBy { it.first }.forEach { (index, marker, value) ->
            append(formatted.substring(cursor, index))
            append(value)
            cursor = index + marker.length
        }
        append(formatted.substring(cursor))
    }
}

@Composable
fun ButlerAppTitle(
    modifier: Modifier = Modifier,
    isUpgraded: Boolean = false,
    style: TextStyle = MaterialTheme.typography.titleLarge,
) {
    Text(
        text = brandTitle(includeQualifier = isUpgraded, highlightQualifier = isUpgraded),
        modifier = modifier,
        style = style,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ButlerAppTitlePreview() {
    ButlerAppTitle(isUpgraded = false)
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ButlerAppTitleUpgradedPreview() {
    ButlerAppTitle(isUpgraded = true)
}

// All three flag combinations the app actually uses, in one place — the pair (true, false) is the
// one that reads as a mistake at a glance, so seeing it render "Butler FOSS" in one uniform color
// is what documents it.
@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun BrandTitlePreview() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = brandTitle(includeQualifier = false, highlightQualifier = false))
        Text(text = brandTitle(includeQualifier = true, highlightQualifier = false))
        Text(text = brandTitle(includeQualifier = true, highlightQualifier = true))
        Text(text = brandTitleText(includeQualifier = true))
    }
}
