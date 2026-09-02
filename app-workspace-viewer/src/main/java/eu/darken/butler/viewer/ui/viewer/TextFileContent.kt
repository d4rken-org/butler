package eu.darken.butler.viewer.ui.viewer

import android.text.format.Formatter
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.viewer.R
import eu.darken.butler.viewer.core.TextPreview
import java.text.NumberFormat

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

    SelectionContainer(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .let { base -> barScrollConnections.fold(base) { acc, c -> acc.nestedScroll(c) } }
                // Not `clickable`: this is a whole scrolling list, and a click role plus a ripple
                // on it would be wrong.
                .let { base ->
                    if (onToggleChrome == null) base
                    else base.pointerInput(onToggleChrome) { detectTapGestures { onToggleChrome() } }
                },
            contentPadding = contentPadding,
        ) {
            items(preview.lines.size) { index ->
                // Each line is laid out left to right whatever the UI's direction is, and only the
                // line: the list keeps the UI's direction, so its insets stay where the chrome put
                // them. A line's own script still decides how its glyphs run - Arabic inside a line
                // still reads right to left - but the line as a whole starts at the left edge, which
                // is where a line of a log or a config begins. Under an RTL UI they would start at
                // the right instead, so every line opened scrolled to its far end and a log could
                // not be read without dragging each one of them back.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
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
    }
}

/**
 * Says the preview stops short and offers the one thing that does not.
 *
 * A bar in the viewer's chrome rather than an item at the end of the list: the cut is a property of
 * the whole preview, and a reader who never scrolls to the end would otherwise take a partial file
 * for the whole one. [onOpenInEditor] is null where the Editor has nothing to open.
 */
@Composable
fun TextTruncationBar(
    modifier: Modifier = Modifier,
    truncation: TextPreview.Truncation,
    onOpenInEditor: (() -> Unit)?,
) {
    val context = LocalContext.current
    val notice = remember(truncation, context) {
        when (truncation) {
            is TextPreview.Truncation.Bytes -> context.getString(
                R.string.viewer_text_truncated_bytes,
                Formatter.formatShortFileSize(context, truncation.limit),
            )

            is TextPreview.Truncation.Lines -> context.getString(
                R.string.viewer_text_truncated_lines,
                NumberFormat.getIntegerInstance().format(truncation.limit),
            )

            is TextPreview.Truncation.LineWidth -> context.getString(R.string.viewer_text_truncated_width)
        }
    }
    Card(modifier = modifier) {
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
                text = notice,
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
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TextTruncationBarPreview() {
    TextTruncationBar(truncation = TextPreview.Truncation.Bytes(1024 * 1024), onOpenInEditor = {})
}

/** A 42 kB minified file is not "limited to the first 1 MB" - the width bound is what cut it. */
@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TextTruncationBarWidthPreview() {
    TextTruncationBar(truncation = TextPreview.Truncation.LineWidth(2_000), onOpenInEditor = {})
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TextTruncationBarLinesPreview() {
    TextTruncationBar(truncation = TextPreview.Truncation.Lines(50_000), onOpenInEditor = {})
}

/** Streamed content has no path for the Editor, so the bar states the limit and offers nothing. */
@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TextTruncationBarNoEditorPreview() {
    TextTruncationBar(truncation = TextPreview.Truncation.Bytes(1024 * 1024), onOpenInEditor = null)
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TextFileContentEmptyPreview() {
    TextFileContent(
        preview = TextPreview(
            lines = listOf(""),
            charset = Charsets.UTF_8,
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
