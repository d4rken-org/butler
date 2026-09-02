package eu.darken.butler.viewer.ui.viewer

import android.text.format.Formatter
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.DriveFileRenameOutline
import androidx.compose.material.icons.twotone.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.viewer.R
import eu.darken.butler.viewer.core.TextPreview

/**
 * A read-only look at a text file.
 *
 * Lines are their own list items rather than one long [Text]: a log file runs to hundreds of
 * thousands of lines, and a single text node would lay all of them out at once. That is also why
 * each line scrolls horizontally on its own instead of wrapping - a wrapped line breaks the column
 * alignment that makes a log or a config readable in the first place.
 */
@Composable
fun TextFileContent(
    modifier: Modifier = Modifier,
    preview: TextPreview?,
    failed: Boolean = false,
    /**
     * Gated by the same applicability the action bar uses. Streamed content has no path for the
     * Editor to open, and neither has a file that is gone - a button for either does nothing at all.
     */
    editorAvailable: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    barScrollConnections: List<NestedScrollConnection> = emptyList(),
    onOpenInEditor: () -> Unit = {},
    onRetry: () -> Unit = {},
    onToggleChrome: (() -> Unit)? = null,
) {
    if (preview == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (failed) {
                TextPreviewFailure(
                    onRetry = onRetry,
                    onOpenInEditor = onOpenInEditor.takeIf { editorAvailable },
                )
            } else {
                CircularProgressIndicator()
            }
        }
        return
    }

    // The banner floats over the list like the bars do, so the list has to inset for it as well or
    // its last lines would sit underneath it. Measured rather than assumed: the text wraps on a
    // narrow pane and at large font scales.
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current
    var bannerHeight by remember { mutableStateOf(0.dp) }
    // bannerHeight is measured with the bar inset already inside it, so it REPLACES the bottom
    // inset rather than adding to it. Zero unless the banner is actually on screen, or a preview
    // that stopped being truncated would keep its gap.
    val listBottom = if (preview.isTruncated && bannerHeight > 0.dp) {
        bannerHeight
    } else {
        contentPadding.calculateBottomPadding()
    }
    val listPadding = remember(contentPadding, listBottom, layoutDirection) {
        PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection),
            top = contentPadding.calculateTopPadding(),
            end = contentPadding.calculateEndPadding(layoutDirection),
            bottom = listBottom,
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        SelectionContainer {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .let { base -> barScrollConnections.fold(base) { acc, c -> acc.nestedScroll(c) } }
                    // Not `clickable`: this is a whole scrolling list, and a click role plus a
                    // ripple on it would be wrong.
                    .let { base ->
                        if (onToggleChrome == null) base
                        else base.pointerInput(onToggleChrome) { detectTapGestures { onToggleChrome() } }
                    },
                contentPadding = listPadding,
            ) {
                items(preview.lines.size) { index ->
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp),
                        text = preview.lines[index],
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        softWrap = false,
                    )
                }
            }
        }

        if (preview.isTruncated) {
            TruncationBanner(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { bannerHeight = with(density) { it.height.toDp() } }
                    // Only the bottom inset: the banner sits above the bottom bar, and the other
                    // three would pad its own box rather than move it.
                    .padding(bottom = contentPadding.calculateBottomPadding()),
                limitBytes = preview.limitBytes,
                onOpenInEditor = onOpenInEditor.takeIf { editorAvailable },
            )
        }
    }
}

/**
 * Says the preview stops short and offers the one thing that does not. Anchored to the bottom rather
 * than placed at the end of the list: the cut is a property of the whole preview, and a reader who
 * never scrolls to the end would otherwise take a partial file for the whole one.
 */
@Composable
private fun TruncationBanner(
    modifier: Modifier = Modifier,
    limitBytes: Long,
    onOpenInEditor: (() -> Unit)?,
) {
    val context = LocalContext.current
    val limitText = remember(limitBytes) { Formatter.formatShortFileSize(context, limitBytes) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                modifier = Modifier.size(20.dp),
                imageVector = Icons.TwoTone.WarningAmber,
                contentDescription = null,
            )
            Text(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.viewer_text_truncated_notice, limitText),
                style = MaterialTheme.typography.bodySmall,
            )
            if (onOpenInEditor != null) {
                TextButton(onClick = onOpenInEditor) {
                    Text(text = stringResource(R.string.viewer_open_in_editor_action))
                }
            }
        }
    }
}

@Composable
private fun TextPreviewFailure(
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
    onOpenInEditor: (() -> Unit)?,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            modifier = Modifier.size(48.dp),
            imageVector = Icons.TwoTone.DriveFileRenameOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.viewer_text_unreadable_label),
            style = MaterialTheme.typography.titleMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRetry) {
                Text(text = stringResource(eu.darken.butler.common.R.string.general_retry_action))
            }
            if (onOpenInEditor != null) {
                TextButton(onClick = onOpenInEditor) {
                    Text(text = stringResource(R.string.viewer_open_in_editor_action))
                }
            }
        }
    }
}

private val previewLines = listOf(
    "# Butler config",
    "theme = system",
    "root.enabled = true",
    "",
    "[explorer]",
    "show_hidden = false",
    "sort = name",
)

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TextFileContentPreview() {
    TextFileContent(
        preview = TextPreview(
            lines = previewLines,
            charset = Charsets.UTF_8,
            isTruncated = false,
            limitBytes = 1024 * 1024,
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TextFileContentTruncatedPreview() {
    TextFileContent(
        preview = TextPreview(
            lines = previewLines,
            charset = Charsets.UTF_8,
            isTruncated = true,
            limitBytes = 1024 * 1024,
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TextFileContentEmptyPreview() {
    TextFileContent(
        preview = TextPreview(
            lines = listOf(""),
            charset = Charsets.UTF_8,
            isTruncated = false,
            limitBytes = 1024 * 1024,
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TextFileContentLoadingPreview() {
    TextFileContent(preview = null)
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TextFileContentFailedPreview() {
    TextFileContent(preview = null, failed = true)
}
